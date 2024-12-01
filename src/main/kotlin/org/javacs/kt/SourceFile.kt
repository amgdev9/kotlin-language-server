package org.javacs.kt

import org.javacs.kt.util.filePath
import org.jetbrains.kotlin.backend.common.compilationException
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext
import java.net.URI
import java.nio.file.Paths
import kotlin.concurrent.withLock

data class CompilationResult(val compiledFile: KtFile, val compiledContext: BindingContext, val module: ModuleDescriptor)

class SourceFile(
    val uri: URI,
    val version: Int,
    var content: String,
    var ktFile: KtFile? = null,
    var compilationResult: CompilationResult? = null,
    val isTemporary: Boolean = false, // A temporary source file will not be returned by .all()
    var lastSavedFile: KtFile? = null,
) {
    fun clean() {
        ktFile = null
        compilationResult = null
    }

    fun parse() {
        ktFile = clientSession.sourceFiles.compiler.createKtFile(
            content,
            uri.filePath ?: Paths.get("sourceFile.virtual.kt")
        )
    }

    fun parseIfChanged() {
        if (content != ktFile?.text) {
            parse()
        }
    }

    fun compile() {
        LOG.info("Compiling {}", uri.filePath?.fileName)

        val oldFile = clone()

        val (context, module) = clientSession.sourceFiles.compiler.compileKtFile(ktFile!!, allIncludingThis())
        clientSession.sourceFiles.parseDataWriteLock.withLock {
            compilationResult = CompilationResult(compiledContext = context, module = module, compiledFile = ktFile!!)
        }

        clientSession.sourceFiles.refreshWorkspaceIndexes(listOf(oldFile), listOf(this))
    }

    fun prepareCompiledFile(): CompiledFile {
        parseIfChanged()
        if (compilationResult == null) {
            compile()
        }
        return CompiledFile(
            content = content,
            parse = compilationResult!!.compiledFile,
            compile = compilationResult!!.compiledContext,
            module = compilationResult!!.module,
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
        SourceFile(uri, version, content, ktFile, compilationResult, isTemporary)
}
