package org.javacs.kt

import com.intellij.lang.Language
import com.intellij.openapi.util.text.StringUtil.convertLineSeparators
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.javacs.kt.externalsources.contentOf
import org.javacs.kt.index.refreshIndex
import org.javacs.kt.index.updateIndexes
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.describeURI
import org.javacs.kt.util.filePath
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.CompositeBindingContext
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import java.io.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.extension
import kotlin.streams.asSequence

val javaHome: String? = System.getProperty("java.home", null)

private class SourceVersion(val content: String, val version: Int, val language: Language?, val isTemporary: Boolean)

/**
 * Keep track of the text of all files in the workspace
 */
class SourceFiles {
    lateinit var compiler: Compiler
        private set

    private val sourceVersionFiles = mutableMapOf<URI, SourceVersion>()
    private val sourceFiles = mutableMapOf<URI, SourceFile>()
    private val openFiles = mutableSetOf<URI>()
    private val javaSourcePath = mutableSetOf<Path>()
    private val outputDirectory: File = Files.createTempDirectory("klsBuildOutput").toFile()
    val parseDataWriteLock = ReentrantLock()
    private val indexAsync = AsyncExecutor()

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

            setSourceVersion(uri, sourceVersion)
        }

        LOG.info("Searching java source files...")
        javaSourcePath.addAll(findJavaSourceFiles(clientSession.projectClasspath.javaSourceDirs))

        LOG.info("Instantiating compiler...")
        compiler = Compiler(outputDirectory)
    }

    private fun setSourceVersion(uri: URI, source: SourceVersion) {
        val content = convertLineSeparators(source.content)

        sourceVersionFiles[uri] = source
        putSourceFile(uri, content, source.language, source.isTemporary)
    }

    fun removeSourceVersion(uri: URI) {
        sourceVersionFiles.remove(uri)
        deleteSourceFile(uri)
    }

    fun removeSourceVersionIfTemporary(uri: URI): Boolean {
        if (sourceFiles[uri]!!.isTemporary) {
            LOG.info("Removing temporary source file {} from source path", describeURI(uri))
            deleteSourceFile(uri)
            sourceVersionFiles.remove(uri)
            return true
        } else {
            return false
        }
    }

    fun deleteSourceFile(uri: URI) {
        sourceFiles[uri]?.let {
            refreshWorkspaceIndexes(listOf(it), emptyList())
            clientSession.sourceFiles.compiler.removeGeneratedCode(listOfNotNull(it.lastSavedFile))
        }

        sourceFiles.remove(uri)
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
        setSourceVersion(uri, SourceVersion(content, version, languageOf(uri), isTemporary = false))
        openFiles.add(uri)
    }

    fun close(uri: URI) {
        if (uri !in openFiles) return

        openFiles.remove(uri)
        val removed = removeSourceVersionIfTemporary(uri)
        if (removed) return

        val disk = readFromDisk(uri, temporary = false)

        if (disk != null) {
            setSourceVersion(uri, disk)
        } else {
            removeSourceVersion(uri)
        }
    }

    fun edit(uri: URI, newVersion: Int, contentChanges: List<TextDocumentContentChangeEvent>) {
        val existing = sourceVersionFiles[uri]
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

        setSourceVersion(uri, SourceVersion(newText, newVersion, existing.language, existing.isTemporary))
    }

    fun createdOnDisk(uri: URI) {
        changedOnDisk(uri)

        if (isJavaSource(uri.filePath!!)) {
            javaSourcePath.add(uri.filePath!!)
        }
    }

    fun deletedOnDisk(uri: URI) {
        if (isKotlinSource(uri)) {
            sourceVersionFiles.remove(uri)
        } else if (isJavaSource(uri.filePath!!)) {
            javaSourcePath.remove(uri.filePath!!)
            refreshCompilerAndSourcePath()
        }
    }

    fun changedOnDisk(uri: URI) {
        if (isKotlinSource(uri)) {
            val sourceVersion = readFromDisk(uri, sourceVersionFiles[uri]?.isTemporary == true)
            if (sourceVersion == null) throw RuntimeException("Could not read source file '$uri' after being changed on disk")

            setSourceVersion(uri, sourceVersion)
        } else if(isJavaSource(uri.filePath!!)) {
            refreshCompilerAndSourcePath()
        }
    }

    private fun refreshCompilerAndSourcePath() {
        LOG.info("Reinstantiating compiler")
        compiler.close()
        compiler = Compiler(outputDirectory)
        refresh()
    }

    fun isOpen(uri: URI): Boolean = (uri in openFiles)

    fun close() {
        compiler.close()
        outputDirectory.delete()
    }

    /**
     * Get the latest content of a file
     */
    fun content(uri: URI): String = sourceFiles[uri]!!.content

    fun parsedFile(uri: URI): KtFile {
        val file = sourceFiles[uri]!!
        file.parseIfChanged()
        return file.ktFile!!
    }

    fun putSourceFile(uri: URI, content: String, language: Language?, temporary: Boolean) {
        assert(!content.contains('\r'))

        if (temporary) {
            LOG.info("Adding temporary source file {} to source path", describeURI(uri))
        }

        if (uri in sourceFiles) {
            sourceFiles[uri]!!.put(content)
        } else {
            sourceFiles[uri] = SourceFile(uri, content, language = language, isTemporary = temporary)
        }
    }

    /**
     * Compile the latest version of a file
     */
    fun currentVersion(uri: URI): CompiledFile =
        sourceFiles[uri]!!.apply { compileIfChanged() }.prepareCompiledFile()

    /**
     * Return whatever is the most-recent already-compiled version of `file`
     */
    fun latestCompiledVersion(uri: URI): CompiledFile =
        sourceFiles[uri]!!.prepareCompiledFile()

    /**
     * Compile changed files
     */
    fun compileFiles(all: Collection<URI>): BindingContext {
        // Figure out what has changed
        val sources = all.map { sourceFiles[it]!! }
        val allChanged = sources.filter { it.content != it.compiledFile?.text }

        // Compile changed files
        val sourcesContext = compileAndUpdate(allChanged)

        // Combine with past compilations
        val same = sources - allChanged
        val combined = listOfNotNull(sourcesContext) + same.map { it.compiledContext!! }

        return CompositeBindingContext.create(combined)
    }

    private fun compileAndUpdate(allChanged: List<SourceFile>): BindingContext? {
        if (allChanged.isEmpty()) return null

        // Get clones of the old files, so we can remove the old declarations from the index
        val oldFiles = allChanged.mapNotNull {
            if (it.compiledFile?.text != it.content || it.ktFile?.text != it.content) {
                it.clone()
            } else {
                null
            }
        }

        // Parse the files that have changed
        val parse = allChanged.associateWith { it.apply { parseIfChanged() }.ktFile!! }

        // Get all the files. This will parse them if they changed
        val allFiles = all()
        val (context, module) = clientSession.sourceFiles.compiler.compileKtFiles(parse.values, allFiles)

        // Update cache
        for ((f, parsed) in parse) {
            parseDataWriteLock.withLock {
                if (f.ktFile == parsed) {
                    //only updated if the parsed file didn't change:
                    f.compiledFile = parsed
                    f.compiledContext = context
                    f.module = module
                }
            }
        }

        refreshWorkspaceIndexes(oldFiles, parse.keys.toList())

        return context
    }

    fun compileAllFiles() {
        // TODO: Investigate the possibility of compiling all files at once, instead of iterating here
        // At the moment, compiling all files at once sometimes leads to an internal error from the TopDownAnalyzer
        sourceFiles.keys.forEach {
            // If one of the files fails to compile, we compile the others anyway
            try {
                compileFiles(listOf(it))
            } catch (ex: Exception) {
                LOG.printStackTrace(ex)
            }
        }
    }

    /**
     * Saves a file. This generates code for the file and deletes previously generated code for this file.
     */
    fun save(uri: URI) {
        val file = sourceFiles[uri]
        if (file == null) return

        // If the code generation fails for some reason, we generate code for the other files anyway
        try {
            clientSession.sourceFiles.compiler.removeGeneratedCode(listOfNotNull(file.lastSavedFile))
            val module = file.module
            val context = file.compiledContext
            if (module == null || context == null) return

            clientSession.sourceFiles.compiler.generateCode(module, context, listOfNotNull(file.compiledFile))
            file.lastSavedFile = file.compiledFile
        } catch (ex: Exception) {
            LOG.printStackTrace(ex)
        }
    }

    fun saveAllFiles() {
        sourceFiles.keys.forEach { save(it) }
    }

    fun refreshDependencyIndexes() {
        compileAllFiles()

        val module = sourceFiles.values.first { it.module != null }.module
        if (module == null) return

        val declarations = getDeclarationDescriptors(sourceFiles.values)
        refreshIndex(module, declarations)
    }

    /**
     * Refreshes the indexes. If already done, refreshes only the declarations in the files that were changed.
     */
    fun refreshWorkspaceIndexes(oldFiles: List<SourceFile>, newFiles: List<SourceFile>) = indexAsync.execute {
        val oldDeclarations = getDeclarationDescriptors(oldFiles)
        val newDeclarations = getDeclarationDescriptors(newFiles)

        // Index the new declarations in the Kotlin source files that were just compiled, removing the old ones
        updateIndexes(oldDeclarations, newDeclarations)
    }

    // Gets all the declaration descriptors for the collection of files
    private fun getDeclarationDescriptors(files: Collection<SourceFile>) =
        files.flatMap { file ->
            val compiledFile = file.compiledFile ?: file.ktFile
            val module = file.module
            if (compiledFile == null || module == null) return@flatMap emptyList()

            return@flatMap module.getPackage(compiledFile.packageFqName).memberScope.getContributedDescriptors(
                DescriptorKindFilter.ALL
            ) { name -> compiledFile.declarations.map { it.name }.contains(name.toString()) }
        }.asSequence()

    /**
     * Recompiles all source files that are initialized.
     */
    fun refresh() {
        val initialized = sourceFiles.values.any { it.ktFile != null }
        if (!initialized) return

        LOG.info("Refreshing source path")
        sourceFiles.values.forEach { it.clean() }
        sourceFiles.values.forEach { it.compile() }
    }

    /**
     * Get parsed trees for all .kt files on source path
     */
    fun all(includeHidden: Boolean = false): Collection<KtFile> =
        sourceFiles.values
            .asSequence()
            .filter { includeHidden || !it.isTemporary }
            .map { it.apply { parseIfChanged() }.ktFile!! }
            .toList()
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
