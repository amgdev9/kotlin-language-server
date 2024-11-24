package org.javacs.kt.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.services.JsonDelegate
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.javacs.kt.ClientSession
import org.javacs.kt.CompilerClassPath
import org.javacs.kt.Configuration
import org.javacs.kt.LOG
import org.javacs.kt.LogLevel
import org.javacs.kt.LogMessage
import org.javacs.kt.SourceFiles
import org.javacs.kt.SourcePath
import org.javacs.kt.URIContentProvider
import org.javacs.kt.externalsources.*
import org.javacs.kt.actions.semanticTokensLegend
import org.javacs.kt.classpath.getGradleProjectInfo
import org.javacs.kt.clientSession
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.TemporaryFolder
import org.javacs.kt.util.parseURI
import org.javacs.kt.db.setupDB
import java.io.Closeable
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import kotlin.system.exitProcess

class KotlinLanguageServer(
    val config: Configuration = Configuration()
) : LanguageServer, LanguageClientAware, Closeable {
    private val tempDirectory = TemporaryFolder()
    private val uriContentProvider = URIContentProvider(
        ClassContentProvider(
            tempDirectory,
            CompositeSourceArchiveProvider(
                JdkSourceArchiveProvider(),
                ClassPathSourceArchiveProvider()
            )
        )
    )

    private val sourcePath by lazy { SourcePath(uriContentProvider) }
    private val sourceFiles by lazy { SourceFiles(sourcePath, uriContentProvider) }

    private val textDocuments by lazy {
        KotlinTextDocumentService(sourceFiles, sourcePath, config, tempDirectory, uriContentProvider)
    }
    private val workspaces by lazy { KotlinWorkspaceService(sourceFiles, sourcePath, textDocuments, config) }
    private val protocolExtensions by lazy { KotlinProtocolExtensionService(uriContentProvider, sourcePath) }

    private lateinit var client: LanguageClient

    private val async = AsyncExecutor()

    companion object {
        const val VERSION = "1.0.0"
    }

    override fun connect(client: LanguageClient) {
        this.client = client
    }

    override fun getTextDocumentService(): KotlinTextDocumentService = textDocuments
    override fun getWorkspaceService(): KotlinWorkspaceService = workspaces

    @JsonDelegate
    fun getProtocolExtensionService(): KotlinProtocolExtensions = protocolExtensions

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> = async.compute {
        val serverCapabilities = ServerCapabilities().apply {
            setTextDocumentSync(TextDocumentSyncKind.Incremental)
            workspace = WorkspaceServerCapabilities()
            workspace.workspaceFolders = WorkspaceFoldersOptions().apply {
                supported = true
                changeNotifications = Either.forRight(true)
            }
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

        if (clientCapabilities?.textDocument?.rename?.prepareSupport == true) {
            serverCapabilities.renameProvider = Either.forRight(RenameOptions(false))
        }

        val folders = params.workspaceFolders
        if(folders.isEmpty()) {
            throw RuntimeException("No workspace folders specified!")
        }
        val folder = folders.first()
        val root = Paths.get(parseURI(folder.uri))

        clientSession = ClientSession(
            db = setupDB(root),
            client = client,
            classPath = CompilerClassPath(config.compiler, config.codegen)
        )

        if (folders.size > 1) {
            LOG.info("Detected ${folders.size} workspace folders, picking the first one...")
        }

        LOG.info("Adding workspace folder {}", folder.name)

        val projectInfo = getGradleProjectInfo(root)

        sourceFiles.addWorkspaceRoot(root, projectInfo)

        // This calls gradle and reinstantiates the compiler if classpath has changed
        val refreshedCompiler = clientSession.classPath.addWorkspaceRoot(root, projectInfo)
        if (refreshedCompiler) {
            // Recompiles all source files, updating the index
            // TODO Is this needed?
            sourcePath.refresh()
        }

        // This compiles all the files twice and updates the index twice
        // TODO Optimize this mess
        textDocuments.lintAll()

        val serverInfo = ServerInfo("Kotlin Language Server", VERSION)
        LOG.info("Kotlin Language Server: Version $VERSION")
        InitializeResult(serverCapabilities, serverInfo)
    }

    override fun close() {
        textDocumentService.close()
        clientSession.classPath.close()
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
