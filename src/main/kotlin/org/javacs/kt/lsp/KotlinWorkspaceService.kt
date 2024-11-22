package org.javacs.kt.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.WorkspaceService
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.javacs.kt.actions.workspaceSymbols
import org.javacs.kt.util.filePath
import org.javacs.kt.util.parseURI
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import com.google.gson.JsonObject
import org.javacs.kt.CompilerClassPath
import org.javacs.kt.Configuration
import org.javacs.kt.LOG
import org.javacs.kt.SourceFiles
import org.javacs.kt.SourcePath

class KotlinWorkspaceService(
    private val sourceFiles: SourceFiles,
    private val sourcePath: SourcePath,
    private val classPath: CompilerClassPath,
    private val textDocService: KotlinTextDocumentService,
    private val config: Configuration
) : WorkspaceService, LanguageClientAware {
    private var languageClient: LanguageClient? = null

    override fun connect(client: LanguageClient) {
        languageClient = client
    }

    override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> {
        LOG.info("Executing command: {} with {}", params.command, params.arguments)

        return CompletableFuture.completedFuture(null)
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        for (change in params.changes) {
            val uri = parseURI(change.uri)
            val path = uri.filePath

            when (change.type) {
                FileChangeType.Created -> {
                    sourceFiles.createdOnDisk(uri)
                    path?.let(classPath::createdOnDisk)?.let { if (it) sourcePath.refresh() }
                }
                FileChangeType.Deleted -> {
                    sourceFiles.deletedOnDisk(uri)
                    path?.let(classPath::deletedOnDisk)?.let { if (it) sourcePath.refresh() }
                }
                FileChangeType.Changed -> {
                    sourceFiles.changedOnDisk(uri)
                    path?.let(classPath::changedOnDisk)?.let { if (it) sourcePath.refresh() }
                }
            }
        }
    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        val settings = params.settings as? JsonObject
        settings?.get("kotlin")?.asJsonObject?.apply {
            // Update deprecated configuration keys
            get("debounceTime")?.asLong?.let {
                config.diagnostics.debounceTime = it
                textDocService.updateDebouncer()
            }
            get("snippetsEnabled")?.asBoolean?.let { config.completion.snippets.enabled = it }

            // Update compiler options
            get("compiler")?.asJsonObject?.apply {
                val compiler = config.compiler
                get("jvm")?.asJsonObject?.apply {
                    val jvm = compiler.jvm
                    get("target")?.asString?.let {
                        jvm.target = it
                        classPath.updateCompilerConfiguration()
                    }
                }
            }

            // Update options for inlay hints
            get("inlayHints")?.asJsonObject?.apply {
                val inlayHints = config.inlayHints
                get("typeHints")?.asBoolean?.let { inlayHints.typeHints = it }
                get("parameterHints")?.asBoolean?.let { inlayHints.parameterHints = it }
                get("chainedHints")?.asBoolean?.let { inlayHints.chainedHints = it }
            }

            // Update diagnostics options
            // Note that the 'linting' key is deprecated and only kept
            // for backwards compatibility.
            for (diagnosticsKey in listOf("linting", "diagnostics")) {
                get(diagnosticsKey)?.asJsonObject?.apply {
                    val diagnostics = config.diagnostics
                    get("enabled")?.asBoolean?.let {
                        diagnostics.enabled = it
                    }
                    get("level")?.asString?.let {
                        diagnostics.level = when (it.lowercase()) {
                            "error" -> DiagnosticSeverity.Error
                            "warning" -> DiagnosticSeverity.Warning
                            "information" -> DiagnosticSeverity.Information
                            else -> DiagnosticSeverity.Hint
                        }
                    }
                    get("debounceTime")?.asLong?.let {
                        diagnostics.debounceTime = it
                        textDocService.updateDebouncer()
                    }
                }
            }

            // Update code generation options
            get("codegen")?.asJsonObject?.apply {
                val codegen = config.codegen
                get("enabled")?.asBoolean?.let { codegen.enabled = it }
            }

            // Update code-completion options
            get("completion")?.asJsonObject?.apply {
                val completion = config.completion
                get("snippets")?.asJsonObject?.apply {
                    val snippets = completion.snippets
                    get("enabled")?.asBoolean?.let { snippets.enabled = it }
                }
            }

            // Update indexing options
            get("indexing")?.asJsonObject?.apply {
                val indexing = config.indexing
                get("enabled")?.asBoolean?.let {
                    indexing.enabled = it
                }
            }

            // Update options about external sources e.g. JAR files, decompilers, etc
            get("externalSources")?.asJsonObject?.apply {
                val externalSources = config.externalSources
                get("useKlsScheme")?.asBoolean?.let { externalSources.useKlsScheme = it }
            }
        }

        LOG.info("Updated configuration: {}", settings)
    }

    override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> {
        val result = workspaceSymbols(params.query, sourcePath)

        return CompletableFuture.completedFuture(Either.forRight(result))
    }

    override fun didChangeWorkspaceFolders(params: DidChangeWorkspaceFoldersParams) {
        for (change in params.event.removed) {
            LOG.info("Dropping workspace {} from source path", change.uri)

            val root = Paths.get(parseURI(change.uri))

            sourceFiles.removeWorkspaceRoot(root)
            val refreshed = classPath.removeWorkspaceRoot(root)
            if (refreshed) {
                sourcePath.refresh()
            }
        }

        for (change in params.event.added) {
            LOG.info("Adding workspace {} to source path", change.uri)

            val root = Paths.get(parseURI(change.uri))

            sourceFiles.addWorkspaceRoot(root)
            val refreshed = classPath.addWorkspaceRoot(root)
            if (refreshed) {
                sourcePath.refresh()
            }
        }
    }
}
