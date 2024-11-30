package org.javacs.kt

import com.intellij.lang.Language
import org.javacs.kt.index.refreshIndex
import org.javacs.kt.index.updateIndexes
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.describeURI
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.CompositeBindingContext
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import java.net.URI
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SourcePath {
    private val files = mutableMapOf<URI, SourceFile>()
    val parseDataWriteLock = ReentrantLock()

    private val indexAsync = AsyncExecutor()

    fun put(uri: URI, content: String, language: Language?, temporary: Boolean) {
        assert(!content.contains('\r'))

        if (temporary) {
            LOG.info("Adding temporary source file {} to source path", describeURI(uri))
        }

        if (uri in files) {
            files[uri]!!.put(content)
        } else {
            files[uri] = SourceFile(uri, content, language = language, isTemporary = temporary)
        }
    }

    fun deleteIfTemporary(uri: URI): Boolean =
        if (files[uri]!!.isTemporary) {
            LOG.info("Removing temporary source file {} from source path", describeURI(uri))
            delete(uri)
            true
        } else {
            false
        }

    fun delete(uri: URI) {
        files[uri]?.let {
            refreshWorkspaceIndexes(listOf(it), emptyList())
            clientSession.sourceFiles.compiler.removeGeneratedCode(listOfNotNull(it.lastSavedFile))
        }

        files.remove(uri)
    }

    /**
     * Get the latest content of a file
     */
    fun content(uri: URI): String = files[uri]!!.content

    fun parsedFile(uri: URI): KtFile {
        val file = files[uri]!!
        file.parseIfChanged()
        return file.ktFile!!
    }

    /**
     * Compile the latest version of a file
     */
    fun currentVersion(uri: URI): CompiledFile =
        files[uri]!!.apply { compileIfChanged() }.prepareCompiledFile()

    /**
     * Return whatever is the most-recent already-compiled version of `file`
     */
    fun latestCompiledVersion(uri: URI): CompiledFile =
        files[uri]!!.prepareCompiledFile()

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
        files.keys.forEach { save(it) }
    }

    fun refreshDependencyIndexes() {
        compileAllFiles()

        val module = files.values.first { it.module != null }.module
        if (module == null) return

        val declarations = getDeclarationDescriptors(files.values)
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
        files.asSequence().flatMap { file ->
            val compiledFile = file.compiledFile ?: file.ktFile
            val module = file.module
            if (compiledFile == null || module == null) return@flatMap emptyList()

            return@flatMap module.getPackage(compiledFile.packageFqName).memberScope.getContributedDescriptors(
                DescriptorKindFilter.ALL
            ) { name -> compiledFile.declarations.map { it.name }.contains(name.toString()) }
        }

    /**
     * Recompiles all source files that are initialized.
     */
    fun refresh() {
        val initialized = files.values.any { it.ktFile != null }
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
            .map { it.apply { parseIfChanged() }.ktFile!! }
            .toList()
}
