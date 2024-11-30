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

val javaHome: String? = System.getProperty("java.home", null)

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
    lateinit var compiler: Compiler
        private set

    private val files = NotifySourcePath()
    private val openFiles = mutableSetOf<URI>()
    private val javaSourcePath = mutableSetOf<Path>()
    private val outputDirectory: File = Files.createTempDirectory("klsBuildOutput").toFile()

    fun setup() {
        LOG.info("Searching kotlin source files...")
        val addSources = findKotlinSourceFiles(clientSession.projectClasspath.kotlinSourceDirs)

        LOG.info("Adding {} kotlin files to source path", "${addSources.size} files")

        // Load all kotlin files into RAM
        for (uri in addSources) {
            val sourceVersion = readFromDisk(uri, temporary = false)
            if (sourceVersion == null) {
                LOG.warn("Could not read source file '{}'", uri.path)
                continue
            }

            files[uri] = sourceVersion
        }

        LOG.info("Searching java source files...")
        javaSourcePath.addAll(findJavaSourceFiles(clientSession.projectClasspath.javaSourceDirs))

        LOG.info("Instantiating compiler...")
        compiler = Compiler(outputDirectory)
    }

    private fun findJavaSourceFiles(javaSourceDirs: Set<Path>): Set<Path> {
        return javaSourceDirs.asSequence()
            .flatMap {
                Files.walk(it).filter { it.extension == "java" }.asSequence()
            }
            .toSet()
    }

    private fun findKotlinSourceFiles(kotlinSourceDirs: Set<Path>): Set<URI> {
        return kotlinSourceDirs.asSequence()
            .flatMap {
                Files.walk(it).filter { it.extension == "kt" }.asSequence()
            }
            .map { it.toUri() }
            .toSet()
    }

    fun openSourceFile(uri: URI, content: String, version: Int) {
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

        if (isJavaSource(uri.filePath!!)) {
            javaSourcePath.add(uri.filePath!!)
        }
    }

    fun deletedOnDisk(uri: URI) {
        if (isKotlinSource(uri)) {
            files.remove(uri)
        } else if (isJavaSource(uri.filePath!!)) {
            javaSourcePath.remove(uri.filePath!!)
            refreshCompilerAndSourcePath()
        }
    }

    fun changedOnDisk(uri: URI) {
        if (isKotlinSource(uri)) {
            val sourceVersion = readFromDisk(uri, files[uri]?.isTemporary == true)
            if (sourceVersion == null) throw RuntimeException("Could not read source file '$uri' after being changed on disk")

            files[uri] = sourceVersion
        } else if(isJavaSource(uri.filePath!!)) {
            refreshCompilerAndSourcePath()
        }
    }

    private fun refreshCompilerAndSourcePath() {
        LOG.info("Reinstantiating compiler")
        compiler.close()
        compiler = Compiler(outputDirectory)
        clientSession.sourcePath.refresh()
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

    fun isOpen(uri: URI): Boolean = (uri in openFiles)

    fun close() {
        compiler.close()
        outputDirectory.delete()
    }
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

private fun isKotlinSource(uri: URI): Boolean = uri.filePath?.fileName?.toString()?.endsWith(".kt") == true
private fun isJavaSource(file: Path): Boolean = file.fileName.toString().endsWith(".java")

private fun languageOf(uri: URI): Language? {
    val fileName = uri.filePath?.fileName?.toString() ?: return null
    if (fileName.endsWith(".kt")) return KotlinLanguage.INSTANCE
    return null
}
