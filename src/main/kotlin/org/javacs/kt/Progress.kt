package org.javacs.kt

import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.WorkDoneProgressBegin
import org.eclipse.lsp4j.WorkDoneProgressCreateParams
import org.eclipse.lsp4j.WorkDoneProgressEnd
import org.eclipse.lsp4j.WorkDoneProgressNotification
import org.eclipse.lsp4j.WorkDoneProgressReport
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import java.util.UUID
import java.util.concurrent.CompletableFuture

class LanguageClientProgress(
    private val label: String,
    private val token: Either<String, Int>,
    private val client: LanguageClient
) {
    init {
        reportProgress(WorkDoneProgressBegin().also {
            it.title = "Kotlin: $label"
            it.percentage = 0
        })
    }

    fun update(message: String?, percent: Int?) {
        reportProgress(WorkDoneProgressReport().also {
            it.message = message
            it.percentage = percent
        })
    }

    fun close() {
        reportProgress(WorkDoneProgressEnd())
    }

    private fun reportProgress(notification: WorkDoneProgressNotification) {
        client.notifyProgress(ProgressParams(token, Either.forLeft(notification)))
    }

    class Factory(private val client: LanguageClient) {
        fun create(label: String): CompletableFuture<LanguageClientProgress> {
            val token = Either.forLeft<String, Int>(UUID.randomUUID().toString())
            return client
                .createProgress(WorkDoneProgressCreateParams().also {
                    it.token = token
                })
                .thenApply { LanguageClientProgress(label, token, client) }
        }
    }
}