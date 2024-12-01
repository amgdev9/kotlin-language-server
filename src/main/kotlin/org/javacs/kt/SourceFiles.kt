package org.javacs.kt

import com.intellij.openapi.util.text.StringUtil.convertLineSeparators
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.javacs.kt.externalsources.contentOf
import org.javacs.kt.index.rebuildIndex
import org.javacs.kt.index.updateIndexes
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.describeURI
import org.javacs.kt.util.filePath
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

/**
 * Keep track of the text of all files in the workspace
 */
class SourceFiles {
    lateinit var compiler: Compiler
        private set

    private val sourceFiles = mutableMapOf<URI, SourceFile>()
    private val openFiles = mutableSetOf<URI>()
    private val javaSourcePath = mutableSetOf<Path>()
    private val outputDirectory: File = Files.createTempDirectory("klsBuildOutput").toFile()
    val parseDataWriteLock = ReentrantLock()
    private val indexAsync = AsyncExecutor("index")

    fun setup() {
        LOG.info("Searching kotlin source files...")
        val addSources = findKotlinSourceFiles(clientSession.projectClasspath.kotlinSourceDirs)

        LOG.info("Adding {} kotlin files to source path", "${addSources.size} files")

        // Load all kotlin files into RAM
        for (uri in addSources) {
            sourceFiles[uri] = readFromDisk(uri, temporary = false)!!
        }

        LOG.info("Searching java source files...")
        javaSourcePath.addAll(findJavaSourceFiles(clientSession.projectClasspath.javaSourceDirs))

        LOG.info("Instantiating compiler...")
        compiler = Compiler(outputDirectory)
    }

    fun lintAll() {
        // TODO: Investigate the possibility of compiling all files at once, instead of iterating here
        // At the moment, compiling all files at once sometimes leads to an internal error from the TopDownAnalyzer
        sourceFiles.forEach {
            try {
                compileAndUpdate(listOf(it.value))
            } catch (ex: Exception) {
                LOG.printStackTrace(ex) // If one of the files fails to compile, we compile the others anyway
            }
        }

        sourceFiles.forEach { generateCodeForFile(it.key) }

        val module = sourceFiles.values.first { it.compilationResult != null }.compilationResult!!.module

        val declarations = getDeclarationDescriptors(sourceFiles.values)
        rebuildIndex(module, declarations)
    }

    private fun setSourceFile(uri: URI, source: SourceFile) {
        val content = convertLineSeparators(source.content)

        if (source.isTemporary) {
            LOG.info("Adding temporary source file {} to source path", describeURI(uri))
        }

        val sourceFile = sourceFiles[uri]
        if (sourceFile != null) {
            sourceFile.content = content
        } else {
            sourceFiles[uri] = SourceFile(uri = uri, version = source.version, content = content, isTemporary = source.isTemporary)
        }
    }

    fun removeSourceFile(uri: URI) {
        sourceFiles[uri]?.let {
            refreshWorkspaceIndexes(listOf(it), emptyList())
            clientSession.sourceFiles.compiler.removeGeneratedCode(listOfNotNull(it.lastSavedFile))
        }

        sourceFiles.remove(uri)
    }

    fun removeSourceFileIfTemporary(uri: URI): Boolean {
        if (!sourceFiles[uri]!!.isTemporary) return false

        LOG.info("Removing temporary source file {} from source path", describeURI(uri))
        removeSourceFile(uri)
        return true
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
        setSourceFile(uri, SourceFile(uri = uri, content = content, version = version, isTemporary = false))
        openFiles.add(uri)
    }

    fun closeSourceFile(uri: URI) {
        if (uri !in openFiles) return

        openFiles.remove(uri)
        val removed = removeSourceFileIfTemporary(uri)
        if (removed) return

        val disk = readFromDisk(uri, temporary = false)

        if (disk != null) {
            setSourceFile(uri, disk)
        } else {
            removeSourceFile(uri)
        }
    }

    fun edit(uri: URI, newVersion: Int, contentChanges: List<TextDocumentContentChangeEvent>) {
        val existing = sourceFiles[uri]!!

        var newText = existing.content

        if (newVersion <= existing.version) {
            LOG.warn("Ignored {} version {}", describeURI(uri), newVersion)
            return
        }

        for (change in contentChanges) {
            if (change.range == null) newText = change.text
            else newText = patch(newText, change)
        }

        setSourceFile(
            uri,
            SourceFile(
                uri = existing.uri,
                content = newText,
                version = newVersion,
                isTemporary = existing.isTemporary
            )
        )
    }

    fun createdOnDisk(uri: URI) {
        changedOnDisk(uri)

        if (isJavaSource(uri.filePath!!)) {
            javaSourcePath.add(uri.filePath!!)
        }
    }

    fun deletedOnDisk(uri: URI) {
        if (isKotlinSource(uri)) {
            sourceFiles.remove(uri)
        } else if (isJavaSource(uri.filePath!!)) {
            javaSourcePath.remove(uri.filePath!!)
            refreshCompilerAndSourcePath()
        }
    }

    fun changedOnDisk(uri: URI) {
        if (isKotlinSource(uri)) {
            val sourceVersion = readFromDisk(uri, sourceFiles[uri]?.isTemporary == true)!!

            setSourceFile(uri, sourceVersion)
        } else if(isJavaSource(uri.filePath!!)) {
            refreshCompilerAndSourcePath()
        }
    }

    private fun refreshCompilerAndSourcePath() {
        LOG.info("Reinstantiating compiler")
        compiler.close()
        compiler = Compiler(outputDirectory)

        val initialized = sourceFiles.values.any { it.ktFile != null }
        if (!initialized) return

        LOG.info("Refreshing source path")
        sourceFiles.values.forEach { it.clean() }
        sourceFiles.values.forEach {
            it.parse()
            it.compile()
        }
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

    /**
     * Compile the latest version of a file
     */
    fun currentVersion(uri: URI): CompiledFile {
        val sourceFile = sourceFiles[uri]!!
        sourceFile.parseIfChanged()
        if (sourceFile.ktFile?.text != sourceFile.compilationResult?.compiledFile?.text) {
            sourceFile.compile()
        }
        return sourceFile.prepareCompiledFile()
    }

    /**
     * Return whatever is the most-recent already-compiled version of `file`
     */
    fun latestCompiledVersion(uri: URI): CompiledFile {
        return sourceFiles[uri]!!.prepareCompiledFile()
    }

    /**
     * Compile changed files
     */
    fun compileFiles(all: Collection<URI>): BindingContext {
        // Figure out what has changed
        val sources = all.map { sourceFiles[it]!! }
        val allChanged = sources.filter { it.content != it.compilationResult?.compiledFile?.text }

        // Compile changed files
        val sourcesContext = compileAndUpdate(allChanged)

        // Combine with past compilations
        val same = sources - allChanged
        val combined = listOfNotNull(sourcesContext) + same.map { it.compilationResult!!.compiledContext }

        return CompositeBindingContext.create(combined)
    }

    private fun compileAndUpdate(allChanged: List<SourceFile>): BindingContext? {
        if (allChanged.isEmpty()) return null

        // Get clones of the old files, so we can remove the old declarations from the index
        val oldFiles = allChanged.mapNotNull {
            if (it.compilationResult?.compiledFile?.text != it.content || it.ktFile?.text != it.content) {
                it.clone()
            } else {
                null
            }
        }

        // Parse the files that have changed
        val parsedKtFiles = allChanged.associateWith {
            it.parseIfChanged()
            return@associateWith it.ktFile!!
        }

        // Get all the files. This will parse them if they changed
        val allFiles = all()
        val (context, module) = clientSession.sourceFiles.compiler.compileKtFiles(parsedKtFiles.values, allFiles)

        // Update cache
        for ((sourceFile, ktFile) in parsedKtFiles) {
            parseDataWriteLock.withLock {
                if (sourceFile.ktFile == ktFile) {
                    //only updated if the parsed file didn't change:
                    sourceFile.compilationResult =
                        CompilationResult(compiledFile = ktFile, compiledContext = context, module = module)
                }
            }
        }

        refreshWorkspaceIndexes(oldFiles, parsedKtFiles.keys.toList())

        return context
    }

    fun generateCodeForFile(uri: URI) {
        val file = sourceFiles[uri]!!

        // If the code generation fails for some reason, we generate code for the other files anyway
        try {
            val compResult = file.compilationResult
            if (compResult == null) return

            clientSession.sourceFiles.compiler.removeGeneratedCode(listOfNotNull(file.lastSavedFile))
            clientSession.sourceFiles.compiler.generateCode(compResult.module, compResult.compiledContext, listOfNotNull(compResult.compiledFile))

            file.lastSavedFile = compResult.compiledFile
        } catch (ex: Exception) {
            LOG.printStackTrace(ex)
        }
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
            val compResult = file.compilationResult
            if(compResult == null) return@flatMap emptyList()

            val compiledFile = compResult.compiledFile
            val module = compResult.module

            return@flatMap module.getPackage(compiledFile.packageFqName).memberScope.getContributedDescriptors(
                DescriptorKindFilter.ALL
            ) { name -> compiledFile.declarations.map { it.name }.contains(name.toString()) }
        }.asSequence()

    /**
     * Get parsed trees for all .kt files on source path
     */
    fun all(includeHidden: Boolean = false): Collection<KtFile> =
        sourceFiles.values
            .asSequence()
            .filter { includeHidden || !it.isTemporary }
            .map {
                it.parseIfChanged()
                return@map it.ktFile!!
            }
            .toList()
}

private fun readFromDisk(uri: URI, temporary: Boolean): SourceFile? = try {
    val content = contentOf(uri)
    SourceFile(uri = uri, version = -1, content = convertLineSeparators(content), isTemporary = temporary)
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
