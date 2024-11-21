package org.javacs.kt.codeaction

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.javacs.kt.CompiledFile
import org.javacs.kt.codeaction.quickfix.ImplementAbstractMembersQuickFix
import org.javacs.kt.codeaction.quickfix.AddMissingImportsQuickFix
import org.javacs.kt.util.toPath
import org.javacs.kt.index.SymbolIndex

val QUICK_FIXES = listOf(
    ImplementAbstractMembersQuickFix(),
    AddMissingImportsQuickFix()
)

fun codeActions(file: CompiledFile, index: SymbolIndex, range: Range, context: CodeActionContext): List<Either<Command, CodeAction>> {
    val requestedKinds = context.only ?: listOf(CodeActionKind.Refactor, CodeActionKind.QuickFix)
    return requestedKinds.map {
        when (it) {
            CodeActionKind.Refactor -> emptyList()
            CodeActionKind.QuickFix -> getQuickFixes(file, index, range, context.diagnostics)
            else -> listOf()
        }
    }.flatten()
}

fun getQuickFixes(file: CompiledFile, index: SymbolIndex, range: Range, diagnostics: List<Diagnostic>): List<Either<Command, CodeAction>> {
    return QUICK_FIXES.flatMap {
        it.compute(file, index, range, diagnostics)
    }
}
