package org.javacs.kt.externalsources

import java.net.URI
import java.nio.file.Paths

/**
 * Fetches the content of Kotlin files identified by a URI.
 */
class URIContentProvider(
    val classContentProvider: ClassContentProvider
) {
    fun contentOf(uri: URI): String = when (uri.scheme) {
        "file" -> Paths.get(uri).toFile().readText()
        "kls" -> uri.toKlsURI()?.let { classContentProvider.contentOf(it).second }
            ?: throw RuntimeException("Could not find $uri")
        else -> throw RuntimeException("Unrecognized scheme ${uri.scheme}")
    }
}