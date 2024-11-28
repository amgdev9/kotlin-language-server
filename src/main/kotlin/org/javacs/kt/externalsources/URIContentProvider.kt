package org.javacs.kt.externalsources

import java.net.URI
import java.nio.file.Paths

/**
 * Fetches the content of Kotlin files identified by a URI.
 */
fun contentOf(uri: URI): String = when (uri.scheme) {
    "file" -> Paths.get(uri).toFile().readText()
    "kls" -> {
        val klsUri = uri.toKlsURI()
        if (klsUri == null) throw RuntimeException("Could not find $uri")

        classContentOf(klsUri).second
    }
    else -> throw RuntimeException("Unrecognized scheme ${uri.scheme}")
}
