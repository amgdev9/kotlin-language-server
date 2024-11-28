package org.javacs.kt.lsp

import com.google.gson.JsonObject
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.WorkspaceService
import org.javacs.kt.LOG
import org.javacs.kt.actions.workspaceSymbols
import org.javacs.kt.clientSession
import org.javacs.kt.util.filePath
import org.javacs.kt.util.parseURI
import java.util.concurrent.CompletableFuture

class KotlinWorkspaceService: WorkspaceService, LanguageClientAware {
    override fun connect(client: LanguageClient) {}

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        for (change in params.changes) {
            val uri = parseURI(change.uri)
            val path = uri.filePath
            val classPath = clientSession.classPath

            when (change.type) {
                FileChangeType.Created -> {
                    clientSession.sourceFiles.createdOnDisk(uri)
                    path?.let(classPath::createdOnDisk)?.let { if (it) clientSession.sourcePath.refresh() }
                }
                FileChangeType.Deleted -> {
                    clientSession.sourceFiles.deletedOnDisk(uri)
                    path?.let(classPath::deletedOnDisk)?.let { if (it) clientSession.sourcePath.refresh() }
                }
                FileChangeType.Changed -> {
                    clientSession.sourceFiles.changedOnDisk(uri)
                    path?.let(classPath::changedOnDisk)?.let { if (it) clientSession.sourcePath.refresh() }
                }
            }
        }
    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        val settings = params.settings as? JsonObject
        settings?.get("kotlin")?.asJsonObject?.apply {
            val config = clientSession.config

            // Update options for inlay hints
            get("inlayHints")?.asJsonObject?.apply {
                val inlayHints = config.inlayHints
                get("typeHints")?.asBoolean?.let { inlayHints.typeHints = it }
                get("parameterHints")?.asBoolean?.let { inlayHints.parameterHints = it }
                get("chainedHints")?.asBoolean?.let { inlayHints.chainedHints = it }
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
        val result = workspaceSymbols(params.query, clientSession.sourcePath)

        return CompletableFuture.completedFuture(Either.forRight(result))
    }
}
