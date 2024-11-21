package org.javacs.kt.actions

import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DiagnosticTag
import org.javacs.kt.util.toPath
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.rendering.DefaultErrorMessages
import java.net.URI
import org.eclipse.lsp4j.Diagnostic as LangServerDiagnostic
import org.jetbrains.kotlin.diagnostics.Diagnostic as KotlinDiagnostic

fun convertDiagnostic(diagnostic: KotlinDiagnostic): List<Pair<URI, LangServerDiagnostic>> {
    val uri = diagnostic.psiFile.toPath().toUri()
    val content = diagnostic.psiFile.text

    return diagnostic.textRanges.map {
        val item = LangServerDiagnostic(
            range(content, it),
            message(diagnostic),
            severity(diagnostic.severity),
            "kotlin",
            code(diagnostic)
        )
        val factoryName = diagnostic.factory.name

        item.tags = mutableListOf<DiagnosticTag>()
        if ("UNUSED_" in factoryName) item.tags.add(DiagnosticTag.Unnecessary)
        if ("DEPRECATION" in factoryName) item.tags.add(DiagnosticTag.Deprecated)

        Pair(uri, item)
    }
}

private fun code(diagnostic: KotlinDiagnostic) =
        diagnostic.factory.name

private fun message(diagnostic: KotlinDiagnostic) =
        DefaultErrorMessages.render(diagnostic)

private fun severity(severity: Severity): DiagnosticSeverity =
        when (severity) {
            Severity.INFO -> DiagnosticSeverity.Information
            Severity.ERROR -> DiagnosticSeverity.Error
            Severity.WARNING -> DiagnosticSeverity.Warning
        }
