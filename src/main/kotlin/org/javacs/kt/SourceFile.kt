package org.javacs.kt

import com.intellij.lang.Language
import org.javacs.kt.util.fileExtension
import org.javacs.kt.util.filePath
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext
import java.net.URI
import java.nio.file.Paths
import kotlin.concurrent.withLock

class SourceFile(
    val uri: URI,
    val version: Int,
    var content: String,
    var ktFile: KtFile? = null,
    var compiledFile: KtFile? = null,
    var compiledContext: BindingContext? = null,
    var module: ModuleDescriptor? = null,
    val language: Language? = null,
    val isTemporary: Boolean = false, // A temporary source file will not be returned by .all()
    var lastSavedFile: KtFile? = null,
) {
    fun put(newContent: String) {
        content = newContent
    }

    fun clean() {
        ktFile = null
        compiledFile = null
        compiledContext = null
        module = null
    }

    fun parse() {
        // TODO: Create PsiFile using the stored language instead
        val extension = uri.fileExtension ?: "kt" // TODO: Use language?.associatedFileType?.defaultExtension again
        ktFile = clientSession.sourceFiles.compiler.createKtFile(
            content,
            uri.filePath ?: Paths.get("sourceFile.virtual.$extension")
        )
    }

    fun parseIfChanged() {
        if (content != ktFile?.text) {
            parse()
        }
    }

    fun compileIfChanged() {
        parseIfChanged()
        if (ktFile?.text != compiledFile?.text) {
            doCompile()
        }
    }

    fun compile() {
        parse()
        doCompile()
    }

    private fun doCompile() {
        LOG.info("Compiling {}", uri.filePath?.fileName)

        val oldFile = clone()

        val (context, module) = clientSession.sourceFiles.compiler.compileKtFile(ktFile!!, allIncludingThis())
        clientSession.sourceFiles.parseDataWriteLock.withLock {
            compiledContext = context
            this.module = module
            compiledFile = ktFile
        }

        clientSession.sourceFiles.refreshWorkspaceIndexes(listOf(oldFile), listOf(this))
    }

    fun prepareCompiledFile(): CompiledFile {
        parseIfChanged()
        if (compiledFile == null) {
            doCompile()
        }
        return CompiledFile(
            content,
            compiledFile!!,
            compiledContext!!,
            module!!,
            allIncludingThis()
        )
    }

    private fun allIncludingThis(): Collection<KtFile> {
        parseIfChanged()
        if (isTemporary) {
            return (clientSession.sourceFiles.all().asSequence() + sequenceOf(ktFile!!)).toList()
        }
        return clientSession.sourceFiles.all()
    }

    fun clone(): SourceFile =
        SourceFile(uri, version, content, ktFile, compiledFile, compiledContext, module, language, isTemporary)
}
