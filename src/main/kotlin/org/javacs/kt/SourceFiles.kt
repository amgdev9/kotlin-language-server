package org.javacs.kt

import com.intellij.lang.Language
import com.intellij.openapi.util.text.StringUtil.convertLineSeparators
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.javacs.kt.externalsources.contentOf
import org.javacs.kt.util.describeURI
import org.javacs.kt.util.filePath
import org.jetbrains.kotlin.idea.KotlinLanguage
import java.io.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.streams.asSequence

private class SourceVersion(val content: String, val version: Int, val language: Language?, val isTemporary: Boolean)

private class NotifySourcePath() {
    private val files = mutableMapOf<URI, SourceVersion>()

    operator fun get(uri: URI): SourceVersion? = files[uri]

    operator fun set(uri: URI, source: SourceVersion) {
        val content = convertLineSeparators(source.content)

        files[uri] = source
        clientSession.sourcePath.put(uri, content, source.language, source.isTemporary)
    }

    fun remove(uri: URI) {
        files.remove(uri)
        clientSession.sourcePath.delete(uri)
    }

    fun removeIfTemporary(uri: URI): Boolean =
        if (clientSession.sourcePath.deleteIfTemporary(uri)) {
            files.remove(uri)
            true
        } else {
            false
        }
}

/**
 * Keep track of the text of all files in the workspace
 */
class SourceFiles {
    private val files = NotifySourcePath()
    private val openFiles = mutableSetOf<URI>()

    fun open(uri: URI, content: String, version: Int) {
        files[uri] = SourceVersion(content, version, languageOf(uri), isTemporary = false)
        openFiles.add(uri)
    }

    fun close(uri: URI) {
        if (uri !in openFiles) return

        openFiles.remove(uri)
        val removed = files.removeIfTemporary(uri)
        if (removed) return

        val disk = readFromDisk(uri, temporary = false)

        if (disk != null) {
            files[uri] = disk
        } else {
            files.remove(uri)
        }
    }

    fun edit(uri: URI, newVersion: Int, contentChanges: List<TextDocumentContentChangeEvent>) {
        val existing = files[uri]
        if(existing == null) return

        var newText = existing.content

        if (newVersion <= existing.version) {
            LOG.warn("Ignored {} version {}", describeURI(uri), newVersion)
            return
        }

        for (change in contentChanges) {
            if (change.range == null) newText = change.text
            else newText = patch(newText, change)
        }

        files[uri] = SourceVersion(newText, newVersion, existing.language, existing.isTemporary)
    }

    fun createdOnDisk(uri: URI) {
        changedOnDisk(uri)
    }

    fun deletedOnDisk(uri: URI) {
        if (!isSource(uri)) return
        files.remove(uri)
    }

    fun changedOnDisk(uri: URI) {
        if (!isSource(uri)) return

        val sourceVersion = readFromDisk(uri, files[uri]?.isTemporary == true)
        if (sourceVersion == null) throw RuntimeException("Could not read source file '$uri' after being changed on disk")

        files[uri] = sourceVersion
    }

    private fun readFromDisk(uri: URI, temporary: Boolean): SourceVersion? = try {
        val content = contentOf(uri)
        SourceVersion(content, -1, languageOf(uri), isTemporary = temporary)
    } catch (_: FileNotFoundException) {
        null
    } catch (_: IOException) {
        LOG.warn("Exception while reading source file {}", describeURI(uri))
        null
    }

    fun setupWorkspaceRoot() {
        LOG.info("Searching kotlin files...")
        val addSources = findKotlinSourceFiles(clientSession.projectClasspath.kotlinSourceDirs)

        LOG.info("Adding {} to source path", "${addSources.size} files")

        // Load all kotlin files into RAM
        for (uri in addSources) {
            val sourceVersion = readFromDisk(uri, temporary = false)
            if (sourceVersion == null) {
                LOG.warn("Could not read source file '{}'", uri.path)
                continue
            }

            files[uri] = sourceVersion
        }
    }

    private fun findKotlinSourceFiles(kotlinSourceDirs: Set<Path>): Set<URI> {
        return kotlinSourceDirs.asSequence()
            .flatMap {
                Files.walk(it).filter { it.extension == "kt" }.asSequence()
            }
            .map { it.toUri() }
            .toSet()
    }

    fun isOpen(uri: URI): Boolean = (uri in openFiles)
}

private fun patch(sourceText: String, change: TextDocumentContentChangeEvent): String {
    val range = change.range
    val reader = BufferedReader(StringReader(sourceText))
    val writer = StringWriter()

    // Skip unchanged lines
    var line = 0

    while (line < range.start.line) {
        writer.write(reader.readLine() + '\n')
        line++
    }

    // Skip unchanged chars
    for (character in 0 until range.start.character) {
        writer.write(reader.read())
    }

    // Write replacement text
    writer.write(change.text)

    // Skip replaced text
    for (i in 0 until (range.end.line - range.start.line)) {
        reader.readLine()
    }
    if (range.start.line == range.end.line) {
        reader.skip((range.end.character - range.start.character).toLong())
    } else {
        reader.skip(range.end.character.toLong())
    }

    // Write remaining text
    while (true) {
        val next = reader.read()

        if (next == -1) return writer.toString()
        else writer.write(next)
    }
}

private fun isSource(uri: URI): Boolean = languageOf(uri) != null

private fun languageOf(uri: URI): Language? {
    val fileName = uri.filePath?.fileName?.toString() ?: return null
    if (fileName.endsWith(".kt")) return KotlinLanguage.INSTANCE
    return null
}
