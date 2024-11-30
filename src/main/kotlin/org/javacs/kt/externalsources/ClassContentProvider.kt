package org.javacs.kt.externalsources

import org.javacs.kt.LOG
import org.javacs.kt.clientSession
import org.javacs.kt.util.describeURI
import org.javacs.kt.util.TemporaryFolder
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.LinkedHashMap

/**
 * Provides the source code for classes located inside
 * compiled or source archives, such as JARs or ZIPs.
 * Fetches the contents of a compiled class/source file in an archive
 * and another URI which can be used to refer to these extracted
 * contents.
 * If the file is inside a source archive, the source code is returned as is.
 */
fun classContentOf(uri: KlsURI): Pair<KlsURI, String> {
    LOG.info("Resolving {} for contents", uri)
    val resolvedUri = (fetchJdkSourceArchive(uri.archivePath) ?: clientSession.projectClasspath.classPath.firstOrNull { it.compiledJar == uri.archivePath }?.sourceJar)?.let(uri.withSource(true)::withArchivePath) ?: uri
    val key = resolvedUri.toString()
    val (contents, extension) = cachedContents[key] ?: run {
        LOG.info("Reading contents of {}", describeURI(resolvedUri.fileUri))
        tryReadContentOf(clientSession.tempFolder, resolvedUri)
            ?: tryReadContentOf(clientSession.tempFolder, resolvedUri.withFileExtension("class"))
            ?: tryReadContentOf(clientSession.tempFolder, resolvedUri.withFileExtension("java"))
            ?: tryReadContentOf(clientSession.tempFolder, resolvedUri.withFileExtension("kt"))
            ?: throw RuntimeException("Could not find $uri")
    }.also { cachedContents[key] = it }
    val sourceUri = resolvedUri.withFileExtension(extension)
    return Pair(sourceUri, contents)
}

/** Maps recently used (source-)KLS-URIs to their source contents (e.g. decompiled code) and the file extension. */
private val cachedContents = object : LinkedHashMap<String, Pair<String, String>>() {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>) = size > 5
}

private fun tryReadContentOf(tempDir: TemporaryFolder, uri: KlsURI): Pair<String, String>? = try {
    when (uri.fileExtension) {
        "class" -> {
            val result = Files.newInputStream(decompileClass(uri.extractToTemporaryFile(tempDir))).bufferedReader().use(
                BufferedReader::readText
            )
            Pair(result, "java")
        }
        "java" -> if (uri.source) Pair(uri.readContents(), "java") else Pair(uri.readContents(), "kt")
        else -> Pair(uri.readContents(), "kt") // e.g. for Kotlin source files
    }
} catch (_: FileNotFoundException) { null }
