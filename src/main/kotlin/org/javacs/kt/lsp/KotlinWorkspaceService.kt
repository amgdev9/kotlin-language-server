package org.javacs.kt.lsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.WorkspaceService
import org.javacs.kt.actions.workspaceSymbols
import org.javacs.kt.clientSession
import org.javacs.kt.util.parseURI
import java.util.concurrent.CompletableFuture

class KotlinWorkspaceService: WorkspaceService, LanguageClientAware {
    override fun connect(client: LanguageClient) {}
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {}

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        for (change in params.changes) {
            val uri = parseURI(change.uri)

            when (change.type) {
                FileChangeType.Created -> clientSession.sourceFiles.createdOnDisk(uri)
                FileChangeType.Deleted -> clientSession.sourceFiles.deletedOnDisk(uri)
                FileChangeType.Changed -> clientSession.sourceFiles.changedOnDisk(uri)
            }
        }
    }

    override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> {
        val result = workspaceSymbols(params.query)

        return CompletableFuture.completedFuture(Either.forRight(result))
    }
}
