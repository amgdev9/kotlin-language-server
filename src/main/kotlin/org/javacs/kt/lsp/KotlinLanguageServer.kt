package org.javacs.kt.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.javacs.kt.*
import org.javacs.kt.actions.semanticTokensLegend
import org.javacs.kt.loadClasspathFromDisk
import org.javacs.kt.index.setupIndexDB
import org.javacs.kt.externalsources.createDecompilerOutputDirectory
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.TemporaryFolder
import org.javacs.kt.util.parseURI
import java.io.Closeable
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import kotlin.system.exitProcess

class KotlinLanguageServer: LanguageServer, LanguageClientAware, Closeable {
    private val textDocuments = KotlinTextDocumentService()
    private val workspaces = KotlinWorkspaceService()

    private lateinit var client: LanguageClient

    private val async = AsyncExecutor("init")

    override fun connect(client: LanguageClient) {
        this.client = client
    }

    override fun getTextDocumentService(): KotlinTextDocumentService = textDocuments
    override fun getWorkspaceService(): KotlinWorkspaceService = workspaces

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> = async.compute {
        val serverCapabilities = ServerCapabilities().apply {
            setTextDocumentSync(TextDocumentSyncKind.Incremental)
            workspace = WorkspaceServerCapabilities()
            workspace.workspaceFolders = WorkspaceFoldersOptions().apply {
                supported = true
                changeNotifications = Either.forRight(true)
            }
            inlayHintProvider = Either.forLeft(false)
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

        if (clientCapabilities?.textDocument?.rename?.prepareSupport == true) {
            serverCapabilities.renameProvider = Either.forRight(RenameOptions(false))
        }

        // Get root folder
        val folders = params.workspaceFolders
        val folder = folders.first()
        val root = Paths.get(parseURI(folder.uri))

        clientSession = ClientSession(
            db = setupIndexDB(root),
            rootPath = root,
            client = client,
            tempFolder = TemporaryFolder(),
            decompilerOutputDir = createDecompilerOutputDirectory(),
            sourceFiles = SourceFiles(),
            projectClasspath = loadClasspathFromDisk(root)
        )

        LOG.info("Workspace folder {}", folder.name)
        LOG.info("Classpath files: ${clientSession.projectClasspath.classPath.size}")

        // Begin the setup
        clientSession.sourceFiles.setup()

        // This compiles all the files twice and updates the index twice
        // TODO Optimize this mess
        textDocuments.lintAll()

        val serverInfo = ServerInfo("Kotlin Language Server", "1.0.0")
        LOG.info("Initialization done")
        LOG.info("-------------------")
        InitializeResult(serverCapabilities, serverInfo)
    }

    override fun close() {
        textDocumentService.close()
        clientSession.sourceFiles.close()
        clientSession.tempFolder.close()
        async.shutdown()

        LOG.info("Closing language server...")
        exitProcess(0)
    }

    override fun shutdown(): CompletableFuture<Any> {
        close()
        return completedFuture(null)
    }

    override fun exit() {}
}
