package org.javacs.kt

import com.intellij.lang.Language
import org.javacs.kt.util.fileExtension
import org.javacs.kt.util.filePath
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.concurrent.withLock

class SourceFile(
    val uri: URI,
    var content: String,
    val path: Path? = uri.filePath,
    var parsed: KtFile? = null,
    var compiledFile: KtFile? = null,
    var compiledContext: BindingContext? = null,
    var module: ModuleDescriptor? = null,
    val language: Language? = null,
    val isTemporary: Boolean = false, // A temporary source file will not be returned by .all()
    var lastSavedFile: KtFile? = null,
) {
    val extension: String? =
        uri.fileExtension ?: "kt" // TODO: Use language?.associatedFileType?.defaultExtension again

    fun put(newContent: String) {
        content = newContent
    }

    fun clean() {
        parsed = null
        compiledFile = null
        compiledContext = null
        module = null
    }

    fun parse() {
        // TODO: Create PsiFile using the stored language instead
        parsed = clientSession.classPath.compiler.createKtFile(
            content,
            path ?: Paths.get("sourceFile.virtual.$extension")
        )
    }

    fun parseIfChanged() {
        if (content != parsed?.text) {
            parse()
        }
    }

    fun compileIfNull() = parseIfChanged().apply { doCompileIfNull() }

    private fun doCompileIfNull() {
        if (compiledFile == null) {
            doCompileIfChanged()
        }
    }

    fun compileIfChanged() = parseIfChanged().apply { doCompileIfChanged() }

    fun compile() = parse().apply { doCompile() }

    private fun doCompile() {
        LOG.debug("Compiling {}", path?.fileName)

        val oldFile = clone()

        val (context, module) = clientSession.classPath.compiler.compileKtFile(parsed!!, allIncludingThis())
        clientSession.sourcePath.parseDataWriteLock.withLock {
            compiledContext = context
            this.module = module
            compiledFile = parsed
        }

        clientSession.sourcePath.refreshWorkspaceIndexes(listOfNotNull(oldFile), listOfNotNull(this))
    }

    private fun doCompileIfChanged() {
        if (parsed?.text != compiledFile?.text) {
            doCompile()
        }
    }

    fun prepareCompiledFile(): CompiledFile =
        parseIfChanged().apply { compileIfNull() }.let { doPrepareCompiledFile() }

    private fun doPrepareCompiledFile(): CompiledFile =
        CompiledFile(
            content,
            compiledFile!!,
            compiledContext!!,
            module!!,
            allIncludingThis(),
            clientSession.classPath,
            false
        )

    private fun allIncludingThis(): Collection<KtFile> = parseIfChanged().let {
        if (isTemporary) (clientSession.sourcePath.all().asSequence() + sequenceOf(parsed!!)).toList()
        else clientSession.sourcePath.all()
    }

    // Creates a shallow copy
    fun clone(): SourceFile =
        SourceFile(uri, content, path, parsed, compiledFile, compiledContext, module, language, isTemporary)
}