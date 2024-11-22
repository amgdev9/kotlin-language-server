package org.javacs.kt

import com.intellij.openapi.util.text.StringUtil.convertLineSeparators
import com.intellij.lang.Language
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.javacs.kt.util.filePath
import org.javacs.kt.util.describeURIs
import org.javacs.kt.util.describeURI
import java.io.BufferedReader
import java.io.StringReader
import java.io.StringWriter
import java.io.IOException
import java.io.FileNotFoundException
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Path

private class SourceVersion(val content: String, val version: Int, val language: Language?, val isTemporary: Boolean)

/**
 * Notify SourcePath whenever a file changes
 */
private class NotifySourcePath(private val sourcePath: SourcePath) {
    private val files = mutableMapOf<URI, SourceVersion>()

    operator fun get(uri: URI): SourceVersion? = files[uri]

    operator fun set(uri: URI, source: SourceVersion) {
        val content = convertLineSeparators(source.content)

        files[uri] = source
        sourcePath.put(uri, content, source.language, source.isTemporary)
    }

    fun remove(uri: URI) {
        files.remove(uri)
        sourcePath.delete(uri)
    }

    fun removeIfTemporary(uri: URI): Boolean =
        if (sourcePath.deleteIfTemporary(uri)) {
            files.remove(uri)
            true
        } else {
            false
        }

    fun removeAll(rm: Collection<URI>) {
        files -= rm

        rm.forEach(sourcePath::delete)
    }

    val keys get() = files.keys
}

/**
 * Keep track of the text of all files in the workspace
 */
class SourceFiles(
    sourcePath: SourcePath,
    private val contentProvider: URIContentProvider,
    private val scriptsConfig: Configuration.Scripts
) {
    private val workspaceRoots = mutableSetOf<Path>()
    private var exclusions = SourceExclusions(workspaceRoots, scriptsConfig)
    private val files = NotifySourcePath(sourcePath)
    private val open = mutableSetOf<URI>()

    fun open(uri: URI, content: String, version: Int) {
        if (isIncluded(uri)) {
            files[uri] = SourceVersion(content, version, languageOf(uri), isTemporary = false)
            open.add(uri)
        }
    }

    fun close(uri: URI) {
        if (uri !in open) return

        open.remove(uri)
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
        if (!isIncluded(uri)) return

        val existing = files[uri]!!
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
        val content = contentProvider.contentOf(uri)
        SourceVersion(content, -1, languageOf(uri), isTemporary = temporary)
    } catch (_: FileNotFoundException) {
        null
    } catch (_: IOException) {
        LOG.warn("Exception while reading source file {}", describeURI(uri))
        null
    }

    private fun isSource(uri: URI): Boolean = isIncluded(uri) && languageOf(uri) != null

    private fun languageOf(uri: URI): Language? {
        val fileName = uri.filePath?.fileName?.toString() ?: return null
        if (fileName.endsWith(".kt") || fileName.endsWith(".kts")) return KotlinLanguage.INSTANCE
        return null
    }

    fun addWorkspaceRoot(root: Path) {
        LOG.info("Searching $root using exclusions: ${exclusions.excludedPatterns}")
        val addSources = findSourceFiles(root)

        LOG.info("Adding {} under {} to source path", describeURIs(addSources), root)

        // Load all kotlin files into RAM 
        for (uri in addSources) {
            val sourceVersion = readFromDisk(uri, temporary = false)
            if (sourceVersion == null) {
                LOG.warn("Could not read source file '{}'", uri.path)
                continue
            }

            files[uri] = sourceVersion
        }

        workspaceRoots.add(root)
        updateExclusions()
    }

    private fun findSourceFiles(root: Path): Set<URI> {
        val sourceMatcher = FileSystems.getDefault().getPathMatcher("glob:*.{kt,kts}")
        return SourceExclusions(listOf(root), scriptsConfig)
            .walkIncluded()
            .filter { sourceMatcher.matches(it.fileName) }
            .map(Path::toUri)
            .toSet()
    }

    fun removeWorkspaceRoot(root: Path) {
        val rmSources = files.keys.filter { it.filePath?.startsWith(root) ?: false }

        LOG.info("Removing {} under {} to source path", describeURIs(rmSources), root)

        files.removeAll(rmSources)
        workspaceRoots.remove(root)
        updateExclusions()
    }

    fun updateExclusions() {
        exclusions = SourceExclusions(workspaceRoots, scriptsConfig)
        LOG.info("Updated exclusions: ${exclusions.excludedPatterns}")
    }

    fun isOpen(uri: URI): Boolean = (uri in open)

    fun isIncluded(uri: URI): Boolean = exclusions.isURIIncluded(uri)
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
