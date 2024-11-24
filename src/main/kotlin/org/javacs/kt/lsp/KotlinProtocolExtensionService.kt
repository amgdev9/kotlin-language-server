package org.javacs.kt.lsp

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.jsonrpc.services.JsonSegment
import org.javacs.kt.SourcePath
import org.javacs.kt.actions.listOverridableMembers
import org.javacs.kt.actions.offset
import org.javacs.kt.externalsources.contentOf
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.parseURI
import java.util.concurrent.CompletableFuture

@JsonSegment("kotlin")
interface KotlinProtocolExtensions {
    @JsonRequest
    fun jarClassContents(textDocument: TextDocumentIdentifier): CompletableFuture<String?>

    @JsonRequest
    fun overrideMember(position: TextDocumentPositionParams): CompletableFuture<List<CodeAction>>
}

class KotlinProtocolExtensionService(
    private val sourcePath: SourcePath
) : KotlinProtocolExtensions {
    private val async = AsyncExecutor()

    override fun jarClassContents(textDocument: TextDocumentIdentifier): CompletableFuture<String?> = async.compute {
        contentOf(parseURI(textDocument.uri))
    }

    override fun overrideMember(position: TextDocumentPositionParams): CompletableFuture<List<CodeAction>> = async.compute {
        val fileUri = parseURI(position.textDocument.uri)
        val compiledFile = sourcePath.currentVersion(fileUri)
        val cursorOffset = offset(compiledFile.content, position.position)

        listOverridableMembers(compiledFile, cursorOffset)
    }
}
