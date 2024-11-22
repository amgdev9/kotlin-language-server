package org.javacs.kt

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.DiagnosticSeverity
import java.lang.reflect.Type
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

fun getStoragePath(params: InitializeParams): Path? {
    if (params.initializationOptions == null) return null

    val gson = GsonBuilder().registerTypeHierarchyAdapter(Path::class.java, GsonPathConverter()).create()
    val options = gson.fromJson(params.initializationOptions as JsonElement, InitializationOptions::class.java)

    return options?.storagePath
}

data class InitializationOptions(
    // A path to a directory used by the language server to store data. Used for caching purposes.
    val storagePath: Path?
)

class GsonPathConverter : JsonDeserializer<Path?> {

    @Throws(JsonParseException::class)
    override fun deserialize(json: JsonElement, type: Type?, context: JsonDeserializationContext?): Path? {
        return try {
            Paths.get(json.asString)
        } catch (ex: InvalidPathException) {
            LOG.printStackTrace(ex)
            null
        }
    }
}

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
