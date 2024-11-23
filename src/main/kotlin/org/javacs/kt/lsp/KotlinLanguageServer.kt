package org.javacs.kt.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.services.JsonDelegate
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.javacs.kt.CompilerClassPath
import org.javacs.kt.Configuration
import org.javacs.kt.LOG
import org.javacs.kt.LogLevel
import org.javacs.kt.LogMessage
import org.javacs.kt.SourceFiles
import org.javacs.kt.SourcePath
import org.javacs.kt.URIContentProvider
import org.javacs.kt.DatabaseService
import org.javacs.kt.externalsources.*
import org.javacs.kt.getStoragePath
import org.javacs.kt.LanguageClientProgress
import org.javacs.kt.actions.semanticTokensLegend
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.TemporaryFolder
import org.javacs.kt.util.parseURI
import org.javacs.kt.setupDB
import java.io.Closeable
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import kotlin.system.exitProcess

class KotlinLanguageServer(
    val config: Configuration = Configuration()
) : LanguageServer, LanguageClientAware, Closeable {
    val classPath = CompilerClassPath(config.compiler, config.codegen)

    private val tempDirectory = TemporaryFolder()
    private val uriContentProvider = URIContentProvider(
        ClassContentProvider(
            tempDirectory,
            CompositeSourceArchiveProvider(
                JdkSourceArchiveProvider(classPath),
                ClassPathSourceArchiveProvider(classPath)
            )
        )
    )
    val sourcePath = SourcePath(classPath, uriContentProvider, config.indexing)
    val sourceFiles = SourceFiles(sourcePath, uriContentProvider)

    private val textDocuments =
        KotlinTextDocumentService(sourceFiles, sourcePath, config, tempDirectory, uriContentProvider, classPath)
    private val workspaces = KotlinWorkspaceService(sourceFiles, sourcePath, classPath, textDocuments, config)
    private val protocolExtensions = KotlinProtocolExtensionService(uriContentProvider, classPath, sourcePath)

    private lateinit var client: LanguageClient

    private val async = AsyncExecutor()
    private var progressFactory: LanguageClientProgress.Factory? = null
        set(factory) {
            field = factory
            sourcePath.progressFactory = factory
        }

    companion object {
        const val VERSION = "1.0.0"
    }

    init {
        LOG.info("Kotlin Language Server: Version $VERSION")
    }

    override fun connect(client: LanguageClient) {
        this.client = client
        connectLoggingBackend()

        workspaces.connect(client)
        textDocuments.connect(client)

        LOG.info("Connected to client")
    }

    override fun getTextDocumentService(): KotlinTextDocumentService = textDocuments
    override fun getWorkspaceService(): KotlinWorkspaceService = workspaces

    @JsonDelegate
    fun getProtocolExtensionService(): KotlinProtocolExtensions = protocolExtensions

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> = async.compute {
        val serverCapabilities = ServerCapabilities()
        with(serverCapabilities) {
            setTextDocumentSync(TextDocumentSyncKind.Incremental)
            workspace = WorkspaceServerCapabilities()
            workspace.workspaceFolders = WorkspaceFoldersOptions()
            workspace.workspaceFolders.supported = true
            workspace.workspaceFolders.changeNotifications = Either.forRight(true)
            inlayHintProvider = Either.forLeft(true)
            hoverProvider = Either.forLeft(true)
            renameProvider = Either.forLeft(true)
            completionProvider = CompletionOptions(false, listOf("."))
            signatureHelpProvider = SignatureHelpOptions(listOf("(", ","))
            definitionProvider = Either.forLeft(true)
            documentSymbolProvider = Either.forLeft(true)
            workspaceSymbolProvider = Either.forLeft(true)
            referencesProvider = Either.forLeft(true)
            semanticTokensProvider =
                SemanticTokensWithRegistrationOptions(semanticTokensLegend, true, true)
            codeActionProvider = Either.forLeft(true)
            documentFormattingProvider = Either.forLeft(true)
            documentRangeFormattingProvider = Either.forLeft(true)
            executeCommandProvider = ExecuteCommandOptions()
            documentHighlightProvider = Either.forLeft(true)
        }
 
        val clientCapabilities = params.capabilities
        config.completion.snippets.enabled =
            clientCapabilities?.textDocument?.completion?.completionItem?.snippetSupport == true

        if (clientCapabilities?.window?.workDoneProgress == true) {
            progressFactory = LanguageClientProgress.Factory(client)
        }

        if (clientCapabilities?.textDocument?.rename?.prepareSupport == true) {
            serverCapabilities.renameProvider = Either.forRight(RenameOptions(false))
        }

        val progress = params.workDoneToken?.let { LanguageClientProgress("Workspace folders", it, client) }

        val folders = params.workspaceFolders
        if(folders.size == 0) {
            throw RuntimeException("No workspace folders specified!")
        }
        if (folders.size > 1) {
            LOG.info("Detected ${folders.size} workspace folders, picking the first one...")
        }
        val folder = folders.first()
        val root = Paths.get(parseURI(folder.uri))

        val storagePath = getStoragePath(params) ?: root
        setupDB(storagePath)

        LOG.info("Adding workspace folder {}", folder.name)

        progress?.update("Updating source path", 25)

        // TODO Use gradle to find source files
        sourceFiles.addWorkspaceRoot(root)

        progress?.update("Updating class path", 50)

        // This calls gradle and reinstantiates the compiler if classpath has changed
        val refreshedCompiler = classPath.addWorkspaceRoot(root)
        if (refreshedCompiler) {
            progress?.update("Refreshing source path", 75)

            // Recompiles all source files, updating the index
            // TODO Is this needed?
            sourcePath.refresh()
        }
        progress?.close()

        // This compiles all the files twice and updates the index twice
        // TODO Optimize this mess
        textDocuments.lintAll()

        val serverInfo = ServerInfo("Kotlin Language Server", VERSION)
        InitializeResult(serverCapabilities, serverInfo)
    }

    private fun connectLoggingBackend() {
        // Temp logs for debugging
        val logFile = File("/home/amg/Projects/kotlin-language-server/log.txt")
        if(logFile.exists()) logFile.delete()
        logFile.createNewFile()
        
        val backend: (LogMessage) -> Unit = {
            logFile.appendText("${it.level}: ${it.message}\n")
            client.logMessage(MessageParams().apply {
                type = it.level.toLSPMessageType()
                message = it.message
            })
        }
        LOG.connectOutputBackend(backend)
        LOG.connectErrorBackend(backend)
    }

    override fun close() {
        textDocumentService.close()
        classPath.close()
        tempDirectory.close()
        async.shutdown(awaitTermination = true)

        LOG.info("Closing language server...")
        exitProcess(0)
    }

    override fun shutdown(): CompletableFuture<Any> {
        close()
        return completedFuture(null)
    }

    override fun exit() {}
}

private fun LogLevel.toLSPMessageType(): MessageType = when (this) {
    LogLevel.ERROR -> MessageType.Error
    LogLevel.WARN -> MessageType.Warning
    LogLevel.INFO -> MessageType.Info
    else -> MessageType.Log
}
