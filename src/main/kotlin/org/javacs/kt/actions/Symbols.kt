package org.javacs.kt.actions

import com.intellij.psi.PsiElement
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolLocation
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.javacs.kt.SourcePath
import org.javacs.kt.util.containsCharactersInOrder
import org.javacs.kt.util.preOrderTraversal
import org.javacs.kt.util.toPath
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.parents

fun documentSymbols(file: KtFile): List<Either<SymbolInformation, DocumentSymbol>> =
        doDocumentSymbols(file).map { Either.forRight(it) }

private fun doDocumentSymbols(element: PsiElement): List<DocumentSymbol> {
    val children = element.children.flatMap(::doDocumentSymbols)

    return pickImportantElements(element, true)?.let { currentDecl ->
        val file = element.containingFile
        val span = range(file.text, currentDecl.textRange)
        val nameIdentifier = currentDecl.nameIdentifier
        val nameSpan = nameIdentifier?.let { range(file.text, it.textRange) } ?: span
        val symbol = DocumentSymbol(currentDecl.name ?: "<anonymous>", symbolKind(currentDecl), span, nameSpan, null, children)
        listOf(symbol)
    } ?: children
}

fun workspaceSymbols(query: String, sp: SourcePath): List<WorkspaceSymbol> =
        doWorkspaceSymbols(sp)
                .filter { containsCharactersInOrder(it.name!!, query, false) }
                .mapNotNull(::workspaceSymbol)
                .toList()

private fun doWorkspaceSymbols(sp: SourcePath): Sequence<KtNamedDeclaration> =
        sp.all().asSequence().flatMap(::fileSymbols)

private fun fileSymbols(file: KtFile): Sequence<KtNamedDeclaration> =
        file.preOrderTraversal().mapNotNull { pickImportantElements(it, false) }

private fun pickImportantElements(node: PsiElement, includeLocals: Boolean): KtNamedDeclaration? =
        when (node) {
            is KtClassOrObject -> if (node.name == null) null else node
            is KtTypeAlias -> node
            is KtConstructor<*> -> node
            is KtNamedFunction -> if (!node.isLocal || includeLocals) node else null
            is KtProperty -> if (!node.isLocal || includeLocals) node else null
            is KtVariableDeclaration -> if (includeLocals) node else null
            else -> null
        }

private fun workspaceSymbol(decl: KtNamedDeclaration): WorkspaceSymbol? {
    val name = decl.name ?: return null

    return WorkspaceSymbol(name, symbolKind(decl), Either.forRight(workspaceLocation(decl)), symbolContainer(decl))
}

private fun symbolKind(decl: KtNamedDeclaration): SymbolKind =
        when (decl) {
            is KtClassOrObject -> SymbolKind.Class
            is KtTypeAlias -> SymbolKind.Interface
            is KtConstructor<*> -> SymbolKind.Constructor
            is KtNamedFunction -> SymbolKind.Function
            is KtProperty -> SymbolKind.Property
            is KtVariableDeclaration -> SymbolKind.Variable
            else -> throw IllegalArgumentException("Unexpected symbol $decl")
        }

private fun workspaceLocation(decl: KtNamedDeclaration): WorkspaceSymbolLocation {
    val file = decl.containingFile
    val uri = file.toPath().toUri().toString()

    return WorkspaceSymbolLocation(uri)
}

private fun symbolContainer(d: KtNamedDeclaration): String? =
        d.parents
                .filterIsInstance<KtNamedDeclaration>()
                .firstOrNull()
                ?.fqName.toString()
