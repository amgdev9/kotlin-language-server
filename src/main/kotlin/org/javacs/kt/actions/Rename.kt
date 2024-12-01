package org.javacs.kt.actions

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.javacs.kt.CompiledFile

fun renameSymbol(file: CompiledFile, cursor: Int, newName: String): WorkspaceEdit? {
    val (declaration, location) = file.findDeclaration(cursor) ?: return null
    val declarationEdit = Either.forLeft<TextDocumentEdit, ResourceOperation>(
        TextDocumentEdit(
            VersionedTextDocumentIdentifier().apply { uri = location.uri },
            listOf(TextEdit(location.range, newName))
        )
    )

    val referenceEdits = findReferences(declaration).map {
        Either.forLeft<TextDocumentEdit, ResourceOperation>(
            TextDocumentEdit(
                VersionedTextDocumentIdentifier().apply { uri = it.uri },
                listOf(TextEdit(it.range, newName))
            )
        )
    }

    return WorkspaceEdit(listOf(declarationEdit) + referenceEdits)
}
