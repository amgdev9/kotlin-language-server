package org.javacs.kt.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import org.javacs.kt.*
import org.javacs.kt.actions.*
import org.javacs.kt.actions.completion.completions
import org.javacs.kt.codeaction.codeActions
import org.javacs.kt.util.*
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import java.io.Closeable
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture

class KotlinTextDocumentService: TextDocumentService, Closeable {
    private val async = AsyncExecutor()

    var debounceLint = Debouncer(Duration.ofMillis(250L))
    val lintTodo = mutableSetOf<URI>()
    var lintCount = 0

    private val TextDocumentIdentifier.filePath: Path?
        get() = parseURI(uri).filePath

    private enum class Recompile {
        ALWAYS, AFTER_DOT, NEVER
    }

    private fun recover(position: TextDocumentPositionParams): Pair<CompiledFile, Int>? {
        return recover(position.textDocument.uri, position.position, Recompile.NEVER)
    }

    private fun recover(uriString: String, position: Position, recompile: Recompile): Pair<CompiledFile, Int>? {
        val uri = parseURI(uriString)
        val content = clientSession.sourcePath.content(uri)
        val offset = offset(content, position.line, position.character)
        val shouldRecompile = when (recompile) {
            Recompile.ALWAYS -> true
            Recompile.AFTER_DOT -> offset > 0 && content[offset - 1] == '.'
            Recompile.NEVER -> false
        }
        val compiled = if (shouldRecompile) clientSession.sourcePath.currentVersion(uri) else clientSession.sourcePath.latestCompiledVersion(uri)
        return Pair(compiled, offset)
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> = async.compute {
        val (file, _) = recover(params.textDocument.uri, params.range.start, Recompile.NEVER) ?: return@compute emptyList()
        codeActions(file, params.range, params.context)
    }

    override fun inlayHint(params: InlayHintParams): CompletableFuture<List<InlayHint>> = async.compute {
        val (file, _) = recover(params.textDocument.uri, params.range.start, Recompile.ALWAYS) ?: return@compute emptyList()
        provideHints(file, clientSession.config.inlayHints)
    }

    override fun hover(position: HoverParams): CompletableFuture<Hover?> = async.compute {
        LOG.info("Hovering at {}", describePosition(position))

        val (file, cursor) = recover(position) ?: return@compute null
        hoverAt(file, cursor) ?: noResult("No hover found at ${describePosition(position)}", null)
    }

    override fun documentHighlight(position: DocumentHighlightParams): CompletableFuture<List<DocumentHighlight>> = async.compute {
        val (file, cursor) = recover(position.textDocument.uri, position.position, Recompile.NEVER) ?: return@compute emptyList()
        documentHighlightsAt(file, cursor)
    }

    override fun definition(position: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> = async.compute {
        LOG.info("Go-to-definition at {}", describePosition(position))

        val (file, cursor) = recover(position) ?: return@compute Either.forLeft(emptyList())
        goToDefinition(
            file,
            cursor,
            clientSession.config.externalSources,
            clientSession.classPath
        )
            ?.let(::listOf)
            ?.let { Either.forLeft<List<Location>, List<LocationLink>>(it) }
            ?: noResult("Couldn't find definition at ${describePosition(position)}", Either.forLeft(emptyList()))
    }

    override fun codeLens(params: CodeLensParams): CompletableFuture<List<CodeLens>> {
        TODO("not implemented")
    }

    override fun rename(params: RenameParams) = async.compute {
        val (file, cursor) = recover(params) ?: return@compute null
        renameSymbol(file, cursor, clientSession.sourcePath, params.newName)
    }

    override fun completion(position: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> = async.compute {
        LOG.info("Completing at {}", describePosition(position))

        val (file, cursor) = recover(position)
            ?: return@compute Either.forRight(CompletionList()) // TODO: Investigate when to recompile
        val completions = completions(file, cursor, clientSession.config.completion)
        LOG.info("Found {} items", completions.items.size)

        Either.forRight(completions)
    }

    override fun resolveCompletionItem(unresolved: CompletionItem): CompletableFuture<CompletionItem> {
        TODO("not implemented")
    }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> = async.compute {
        LOG.info("Find symbols in {}", describeURI(params.textDocument.uri))

        val uri = parseURI(params.textDocument.uri)
        val parsed = clientSession.sourcePath.parsedFile(uri)

        documentSymbols(parsed)
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        clientSession.sourceFiles.open(uri, params.textDocument.text, params.textDocument.version)
        lintNow(uri)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        // Lint after saving to prevent inconsistent diagnostics
        val uri = parseURI(params.textDocument.uri)
        lintNow(uri)
        debounceLint.schedule {
            clientSession.sourcePath.save(uri)
        }
    }

    override fun signatureHelp(position: SignatureHelpParams): CompletableFuture<SignatureHelp?> = async.compute {
        LOG.info("Signature help at {}", describePosition(position))

        val (file, cursor) = recover(position) ?: return@compute null
        fetchSignatureHelpAt(file, cursor) ?: noResult("No function call around ${describePosition(position)}", null)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        clientSession.sourceFiles.close(uri)
        clearDiagnostics(uri)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        clientSession.sourceFiles.edit(uri, params.textDocument.version, params.contentChanges)
        lintLater(uri)
    }

    override fun references(position: ReferenceParams) = async.compute {
        position.textDocument.filePath
            ?.let { file ->
                val content = clientSession.sourcePath.content(parseURI(position.textDocument.uri))
                val offset = offset(content, position.position.line, position.position.character)
                findReferences(file, offset, clientSession.sourcePath)
            }
    }

    override fun semanticTokensFull(params: SemanticTokensParams) = async.compute {
        LOG.info("Full semantic tokens in {}", describeURI(params.textDocument.uri))

        val uri = parseURI(params.textDocument.uri)
        val file = clientSession.sourcePath.currentVersion(uri)

        val tokens = encodedSemanticTokens(file)

        SemanticTokens(tokens)
    }

    override fun semanticTokensRange(params: SemanticTokensRangeParams) = async.compute {
        LOG.info("Ranged semantic tokens in {}", describeURI(params.textDocument.uri))

        val uri = parseURI(params.textDocument.uri)
        val file = clientSession.sourcePath.currentVersion(uri)

        val tokens = encodedSemanticTokens(file, params.range)
        LOG.info("Found {} tokens", tokens.size)

        SemanticTokens(tokens)
    }

    override fun resolveCodeLens(unresolved: CodeLens): CompletableFuture<CodeLens> {
        TODO("not implemented")
    }

    private fun describePosition(position: TextDocumentPositionParams): String {
        return "${describeURI(position.textDocument.uri)} ${position.position.line + 1}:${position.position.character + 1}"
    }

    fun lintAll() {
        debounceLint.submitImmediately {
            clientSession.sourcePath.compileAllFiles()
            clientSession.sourcePath.saveAllFiles()
            clientSession.sourcePath.refreshDependencyIndexes()
        }
    }

    private fun clearLint(): List<URI> {
        val result = lintTodo.toList()
        lintTodo.clear()
        return result
    }

    private fun lintLater(uri: URI) {
        lintTodo.add(uri)
        debounceLint.schedule(::doLint)
    }

    private fun lintNow(file: URI) {
        lintTodo.add(file)
        debounceLint.submitImmediately(::doLint)
    }

    private fun doLint(cancelCallback: () -> Boolean) {
        LOG.info("Linting {}", describeURIs(lintTodo))
        val files = clearLint()
        val context = clientSession.sourcePath.compileFiles(files)
        if (!cancelCallback.invoke()) {
            reportDiagnostics(files, context.diagnostics)
        }
        lintCount++
    }

    private fun reportDiagnostics(compiled: Collection<URI>, kotlinDiagnostics: Diagnostics) {
        val langServerDiagnostics = kotlinDiagnostics
            .flatMap(::convertDiagnostic)
        val byFile = langServerDiagnostics.groupBy({ it.first }, { it.second })

        for ((uri, diagnostics) in byFile) {
            if (clientSession.sourceFiles.isOpen(uri)) {
                clientSession.client.publishDiagnostics(PublishDiagnosticsParams(uri.toString(), diagnostics))

                LOG.info("Reported {} diagnostics in {}", diagnostics.size, describeURI(uri))
            }
            else LOG.info("Ignore {} diagnostics in {} because it's not open", diagnostics.size, describeURI(uri))
        }

        val noErrors = compiled - byFile.keys
        for (file in noErrors) {
            clearDiagnostics(file)

            LOG.info("No diagnostics in {}", file)
        }

        lintCount++
    }

    private fun clearDiagnostics(uri: URI) {
        clientSession.client.publishDiagnostics(PublishDiagnosticsParams(uri.toString(), listOf()))
    }

    override fun close() {
        val awaitTermination = true
        async.shutdown(awaitTermination)
        debounceLint.shutdown(awaitTermination)
    }
}
