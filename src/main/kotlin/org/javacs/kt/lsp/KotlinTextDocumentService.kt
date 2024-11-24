package org.javacs.kt.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import org.javacs.kt.CompiledFile
import org.javacs.kt.CompilerClassPath
import org.javacs.kt.Configuration
import org.javacs.kt.LOG
import org.javacs.kt.SourceFiles
import org.javacs.kt.SourcePath
import org.javacs.kt.URIContentProvider
import org.javacs.kt.codeaction.codeActions
import org.javacs.kt.actions.completion.completions
import org.javacs.kt.actions.goToDefinition
import org.javacs.kt.actions.convertDiagnostic
import org.javacs.kt.actions.hoverAt
import org.javacs.kt.actions.offset
import org.javacs.kt.actions.findReferences
import org.javacs.kt.actions.encodedSemanticTokens
import org.javacs.kt.actions.fetchSignatureHelpAt
import org.javacs.kt.actions.renameSymbol
import org.javacs.kt.actions.documentHighlightsAt
import org.javacs.kt.actions.provideHints
import org.javacs.kt.actions.documentSymbols
import org.javacs.kt.clientSession
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.Debouncer
import org.javacs.kt.util.TemporaryFolder
import org.javacs.kt.util.describeURI
import org.javacs.kt.util.describeURIs
import org.javacs.kt.util.filePath
import org.javacs.kt.util.noResult
import org.javacs.kt.util.parseURI
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import java.net.URI
import java.io.Closeable
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture

class KotlinTextDocumentService(
    private val sourceFiles: SourceFiles,
    private val sourcePath: SourcePath,
    private val config: Configuration,
    private val tempDirectory: TemporaryFolder,
    private val uriContentProvider: URIContentProvider,
    private val classPath: CompilerClassPath
) : TextDocumentService, Closeable {
    private val async = AsyncExecutor()

    var debounceLint = Debouncer(Duration.ofMillis(config.diagnostics.debounceTime))
    val lintTodo = mutableSetOf<URI>()
    var lintCount = 0

    private val TextDocumentIdentifier.filePath: Path?
        get() = parseURI(uri).filePath

    private enum class Recompile {
        ALWAYS, AFTER_DOT, NEVER
    }

    private fun recover(position: TextDocumentPositionParams, recompile: Recompile): Pair<CompiledFile, Int>? {
        return recover(position.textDocument.uri, position.position, recompile)
    }

    private fun recover(uriString: String, position: Position, recompile: Recompile): Pair<CompiledFile, Int>? {
        val uri = parseURI(uriString)
        val content = sourcePath.content(uri)
        val offset = offset(content, position.line, position.character)
        val shouldRecompile = when (recompile) {
            Recompile.ALWAYS -> true
            Recompile.AFTER_DOT -> offset > 0 && content[offset - 1] == '.'
            Recompile.NEVER -> false
        }
        val compiled = if (shouldRecompile) sourcePath.currentVersion(uri) else sourcePath.latestCompiledVersion(uri)
        return Pair(compiled, offset)
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> = async.compute {
        val (file, _) = recover(params.textDocument.uri, params.range.start, Recompile.NEVER) ?: return@compute emptyList()
        codeActions(file, sourcePath.index, params.range, params.context)
    }

    override fun inlayHint(params: InlayHintParams): CompletableFuture<List<InlayHint>> = async.compute {
        val (file, _) = recover(params.textDocument.uri, params.range.start, Recompile.ALWAYS) ?: return@compute emptyList()
        provideHints(file, config.inlayHints)
    }

    override fun hover(position: HoverParams): CompletableFuture<Hover?> = async.compute {
        LOG.info("Hovering at {}", describePosition(position))

        val (file, cursor) = recover(position, Recompile.NEVER) ?: return@compute null
        hoverAt(file, cursor) ?: noResult("No hover found at ${describePosition(position)}", null)
    }

    override fun documentHighlight(position: DocumentHighlightParams): CompletableFuture<List<DocumentHighlight>> = async.compute {
        val (file, cursor) = recover(position.textDocument.uri, position.position, Recompile.NEVER) ?: return@compute emptyList()
        documentHighlightsAt(file, cursor)
    }

    override fun definition(position: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> = async.compute {
        LOG.info("Go-to-definition at {}", describePosition(position))

        val (file, cursor) = recover(position, Recompile.NEVER) ?: return@compute Either.forLeft(emptyList())
        goToDefinition(
            file,
            cursor,
            uriContentProvider.classContentProvider,
            tempDirectory,
            config.externalSources,
            classPath
        )
            ?.let(::listOf)
            ?.let { Either.forLeft<List<Location>, List<LocationLink>>(it) }
            ?: noResult("Couldn't find definition at ${describePosition(position)}", Either.forLeft(emptyList()))
    }

    override fun codeLens(params: CodeLensParams): CompletableFuture<List<CodeLens>> {
        TODO("not implemented")
    }

    override fun rename(params: RenameParams) = async.compute {
        val (file, cursor) = recover(params, Recompile.NEVER) ?: return@compute null
        renameSymbol(file, cursor, sourcePath, params.newName)
    }

    override fun completion(position: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> = async.compute {
        LOG.info("Completing at {}", describePosition(position))

        val (file, cursor) = recover(position, Recompile.NEVER)
            ?: return@compute Either.forRight(CompletionList()) // TODO: Investigate when to recompile
        val completions = completions(file, cursor, sourcePath.index, config.completion)
        LOG.info("Found {} items", completions.items.size)

        Either.forRight(completions)
    }

    override fun resolveCompletionItem(unresolved: CompletionItem): CompletableFuture<CompletionItem> {
        TODO("not implemented")
    }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> = async.compute {
        LOG.info("Find symbols in {}", describeURI(params.textDocument.uri))

        val uri = parseURI(params.textDocument.uri)
        val parsed = sourcePath.parsedFile(uri)

        documentSymbols(parsed)
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        sourceFiles.open(uri, params.textDocument.text, params.textDocument.version)
        lintNow(uri)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        // Lint after saving to prevent inconsistent diagnostics
        val uri = parseURI(params.textDocument.uri)
        lintNow(uri)
        debounceLint.schedule {
            sourcePath.save(uri)
        }
    }

    override fun signatureHelp(position: SignatureHelpParams): CompletableFuture<SignatureHelp?> = async.compute {
        LOG.info("Signature help at {}", describePosition(position))

        val (file, cursor) = recover(position, Recompile.NEVER) ?: return@compute null
        fetchSignatureHelpAt(file, cursor) ?: noResult("No function call around ${describePosition(position)}", null)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        sourceFiles.close(uri)
        clearDiagnostics(uri)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = parseURI(params.textDocument.uri)
        sourceFiles.edit(uri, params.textDocument.version, params.contentChanges)
        lintLater(uri)
    }

    override fun references(position: ReferenceParams) = async.compute {
        position.textDocument.filePath
            ?.let { file ->
                val content = sourcePath.content(parseURI(position.textDocument.uri))
                val offset = offset(content, position.position.line, position.position.character)
                findReferences(file, offset, sourcePath)
            }
    }

    override fun semanticTokensFull(params: SemanticTokensParams) = async.compute {
        LOG.info("Full semantic tokens in {}", describeURI(params.textDocument.uri))

        val uri = parseURI(params.textDocument.uri)
        val file = sourcePath.currentVersion(uri)

        val tokens = encodedSemanticTokens(file)

        SemanticTokens(tokens)
    }

    override fun semanticTokensRange(params: SemanticTokensRangeParams) = async.compute {
        LOG.info("Ranged semantic tokens in {}", describeURI(params.textDocument.uri))

        val uri = parseURI(params.textDocument.uri)
        val file = sourcePath.currentVersion(uri)

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

    fun updateDebouncer() {
        debounceLint = Debouncer(Duration.ofMillis(config.diagnostics.debounceTime))
    }

    fun lintAll() {
        debounceLint.submitImmediately {
            sourcePath.compileAllFiles()
            sourcePath.saveAllFiles()
            sourcePath.refreshDependencyIndexes()
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
        val context = sourcePath.compileFiles(files)
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
            if (sourceFiles.isOpen(uri)) {
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
