package org.javacs.kt

import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.fileExtension
import org.javacs.kt.util.filePath
import org.javacs.kt.util.describeURI
import com.intellij.lang.Language
import org.javacs.kt.externalsources.contentOf
import org.javacs.kt.index.refreshIndex
import org.javacs.kt.index.updateIndexes
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.CompositeBindingContext
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import kotlin.concurrent.withLock
import java.nio.file.Path
import java.nio.file.Paths
import java.net.URI
import java.util.concurrent.locks.ReentrantLock
import kotlin.sequences.plus
import kotlin.sequences.toList

class SourcePath {
    private val files = mutableMapOf<URI, SourceFile>()
    val parseDataWriteLock = ReentrantLock()

    private val indexAsync = AsyncExecutor()

    var beforeCompileCallback: () -> Unit = {}

    private fun sourceFile(uri: URI): SourceFile {
        if (uri !in files) {
            // Fallback solution, usually *all* source files
            // should be added/opened through SourceFiles
            LOG.warn(
                "Requested source file {} is not on source path, this is most likely a bug. Adding it now temporarily...",
                describeURI(uri)
            )
            put(uri, contentOf(uri), null, temporary = true)
        }
        return files[uri]!!
    }

    fun put(uri: URI, content: String, language: Language?, temporary: Boolean = false) {
        assert(!content.contains('\r'))

        if (temporary) {
            LOG.info("Adding temporary source file {} to source path", describeURI(uri))
        }

        if (uri in files) {
            sourceFile(uri).put(content)
        } else {
            files[uri] = SourceFile(uri, content, language = language, isTemporary = temporary)
        }
    }

    fun deleteIfTemporary(uri: URI): Boolean =
        if (sourceFile(uri).isTemporary) {
            LOG.info("Removing temporary source file {} from source path", describeURI(uri))
            delete(uri)
            true
        } else {
            false
        }

    fun delete(uri: URI) {
        files[uri]?.let {
            refreshWorkspaceIndexes(listOf(it), listOf())
            clientSession.classPath.compiler.removeGeneratedCode(listOfNotNull(it.lastSavedFile))
        }

        files.remove(uri)
    }

    /**
     * Get the latest content of a file
     */
    fun content(uri: URI): String = sourceFile(uri).content

    fun parsedFile(uri: URI): KtFile = sourceFile(uri).apply { parseIfChanged() }.parsed!!

    /**
     * Compile the latest version of a file
     */
    fun currentVersion(uri: URI): CompiledFile =
        sourceFile(uri).apply { compileIfChanged() }.prepareCompiledFile()

    /**
     * Return whatever is the most-recent already-compiled version of `file`
     */
    fun latestCompiledVersion(uri: URI): CompiledFile =
        sourceFile(uri).prepareCompiledFile()

    /**
     * Compile changed files
     */
    fun compileFiles(all: Collection<URI>): BindingContext {
        // Figure out what has changed
        val sources = all.map { files[it]!! }
        val allChanged = sources.filter { it.content != it.compiledFile?.text }

        // Compile changed files
        val sourcesContext = compileAndUpdate(allChanged)

        // Combine with past compilations
        val same = sources - allChanged
        val combined = listOfNotNull(sourcesContext) + same.map { it.compiledContext!! }

        return CompositeBindingContext.create(combined)
    }

    private fun compileAndUpdate(changed: List<SourceFile>): BindingContext? {
        if (changed.isEmpty()) return null

        // Get clones of the old files, so we can remove the old declarations from the index
        val oldFiles = changed.mapNotNull {
            if (it.compiledFile?.text != it.content || it.parsed?.text != it.content) {
                it.clone()
            } else {
                null
            }
        }

        // Parse the files that have changed
        val parse = changed.associateWith { it.apply { parseIfChanged() }.parsed!! }

        // Get all the files. This will parse them if they changed
        val allFiles = all()
        beforeCompileCallback.invoke()
        val (context, module) = clientSession.classPath.compiler.compileKtFiles(parse.values, allFiles)

        // Update cache
        for ((f, parsed) in parse) {
            parseDataWriteLock.withLock {
                if (f.parsed == parsed) {
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
        files.keys.forEach {
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
        val file = files[uri]
        if (file == null) return

        // If the code generation fails for some reason, we generate code for the other files anyway
        try {
            clientSession.classPath.compiler.removeGeneratedCode(listOfNotNull(file.lastSavedFile))
            val module = file.module
            val context = file.compiledContext
            if (module == null || context == null) return

            clientSession.classPath.compiler.generateCode(module, context, listOfNotNull(file.compiledFile))
            file.lastSavedFile = file.compiledFile
        } catch (ex: Exception) {
            LOG.printStackTrace(ex)
        }
    }

    fun saveAllFiles() {
        files.keys.forEach { save(it) }
    }

    fun refreshDependencyIndexes() {
        compileAllFiles()

        val module = files.values.first { it.module != null }.module
        if (module == null) return

        refreshDependencyIndexes(module)
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

    /**
     * Refreshes the indexes. If already done, refreshes only the declarations in the files that were changed.
     */
    private fun refreshDependencyIndexes(module: ModuleDescriptor) = indexAsync.execute {
        val declarations = getDeclarationDescriptors(files.values)
        refreshIndex(module, declarations)
    }

    // Gets all the declaration descriptors for the collection of files
    private fun getDeclarationDescriptors(files: Collection<SourceFile>) =
        files.flatMap { file ->
            val compiledFile = file.compiledFile ?: file.parsed
            val module = file.module
            if (compiledFile != null && module != null) {
                module.getPackage(compiledFile.packageFqName).memberScope.getContributedDescriptors(
                    DescriptorKindFilter.ALL
                ) { name -> compiledFile.declarations.map { it.name }.contains(name.toString()) }
            } else {
                listOf()
            }
        }.asSequence()

    /**
     * Recompiles all source files that are initialized.
     */
    fun refresh() {
        val initialized = files.values.any { it.parsed != null }
        if (!initialized) return

        LOG.info("Refreshing source path")
        files.values.forEach { it.clean() }
        files.values.forEach { it.compile() }
    }

    /**
     * Get parsed trees for all .kt files on source path
     */
    fun all(includeHidden: Boolean = false): Collection<KtFile> =
        files.values
            .asSequence()
            .filter { includeHidden || !it.isTemporary }
            .map { it.apply { parseIfChanged() }.parsed!! }
            .toList()
}
