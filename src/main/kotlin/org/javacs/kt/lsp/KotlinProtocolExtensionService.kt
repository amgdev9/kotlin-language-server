package org.javacs.kt.lsp

import org.eclipse.lsp4j.*
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.parseURI
import org.javacs.kt.actions.resolveMain
import org.javacs.kt.actions.offset
import org.javacs.kt.actions.listOverridableMembers
import java.util.concurrent.CompletableFuture
import java.nio.file.Paths
import org.eclipse.lsp4j.jsonrpc.services.JsonSegment
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.javacs.kt.CompilerClassPath
import org.javacs.kt.SourcePath
import org.javacs.kt.URIContentProvider

@JsonSegment("kotlin")
interface KotlinProtocolExtensions {
    @JsonRequest
    fun jarClassContents(textDocument: TextDocumentIdentifier): CompletableFuture<String?>

    @JsonRequest
    fun buildOutputLocation(): CompletableFuture<String?>

    @JsonRequest
    fun mainClass(textDocument: TextDocumentIdentifier): CompletableFuture<Map<String, Any?>>

    @JsonRequest
    fun overrideMember(position: TextDocumentPositionParams): CompletableFuture<List<CodeAction>>
}

class KotlinProtocolExtensionService(
    private val uriContentProvider: URIContentProvider,
    private val classPath: CompilerClassPath,
    private val sourcePath: SourcePath
) : KotlinProtocolExtensions {
    private val async = AsyncExecutor()

    override fun jarClassContents(textDocument: TextDocumentIdentifier): CompletableFuture<String?> = async.compute {
        uriContentProvider.contentOf(parseURI(textDocument.uri))
    }

    override fun buildOutputLocation(): CompletableFuture<String?> = async.compute {
        this@KotlinProtocolExtensionService.classPath.outputDirectory.absolutePath
    }

    override fun mainClass(textDocument: TextDocumentIdentifier): CompletableFuture<Map<String, Any?>> = async.compute {
        val fileUri = parseURI(textDocument.uri)
        val filePath = Paths.get(fileUri)
        
        // we find the longest one in case both the root and submodule are included
        val workspacePath = this@KotlinProtocolExtensionService.classPath.workspaceRoots.filter {
            filePath.startsWith(it)
        }.map {
            it.toString()
        }.maxByOrNull(String::length) ?: ""
        
        val compiledFile = sourcePath.currentVersion(fileUri)

        resolveMain(compiledFile) + mapOf(
            "projectRoot" to workspacePath
        )
    }

    override fun overrideMember(position: TextDocumentPositionParams): CompletableFuture<List<CodeAction>> = async.compute {
        val fileUri = parseURI(position.textDocument.uri)
        val compiledFile = sourcePath.currentVersion(fileUri)
        val cursorOffset = offset(compiledFile.content, position.position)

        listOverridableMembers(compiledFile, cursorOffset)
    }
}
