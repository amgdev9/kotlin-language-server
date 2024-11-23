package org.javacs.kt

import org.eclipse.lsp4j.DiagnosticSeverity

data class Configuration(
    val codegen: Codegen = Codegen(),
    val compiler: Compiler = Compiler(),
    val completion: Completion = Completion(),
    val diagnostics: Diagnostics = Diagnostics(),
    val indexing: Indexing = Indexing(),
    val externalSources: ExternalSources = ExternalSources(),
    val inlayHints: InlayHints = InlayHints(),
) {
    data class Codegen(
        /** Whether to enable code generation to a temporary build directory for Java interoperability. */
        var enabled: Boolean = false
    )
    data class Compiler(
        val jvm: JVM = JVM()
    ) {
        data class JVM(
            /** Which JVM target the Kotlin compiler uses. See Compiler.jvmTargetFrom for possible values. */
            var target: String = "default"
        )
    }
    data class Completion(
        val snippets: Snippets = Snippets()
    ) {
        data class Snippets(
            /** Whether code completion should return VSCode-style snippets. */
            var enabled: Boolean = true
        )
    }
    data class Diagnostics(
        /** Whether diagnostics are enabled. */
        var enabled: Boolean = true,
        /** The minimum severity of enabled diagnostics. */
        var level: DiagnosticSeverity = DiagnosticSeverity.Hint,
        /** The time interval between subsequent lints in ms. */
        var debounceTime: Long = 250L
    )
    data class Indexing(
        /** Whether an index of global symbols should be built in the background. */
        var enabled: Boolean = true
    )
    data class ExternalSources(
        /** Whether kls-URIs should be sent to the client to describe classes in JARs. */
        var useKlsScheme: Boolean = false
    )
    data class InlayHints(
        var typeHints: Boolean = false,
        var parameterHints: Boolean = false,
        var chainedHints: Boolean = false
    )
}
