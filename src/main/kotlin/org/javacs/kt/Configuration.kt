package org.javacs.kt

data class Configuration(
    val externalSources: ExternalSources = ExternalSources(),
    val inlayHints: InlayHints = InlayHints(),
) {
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
