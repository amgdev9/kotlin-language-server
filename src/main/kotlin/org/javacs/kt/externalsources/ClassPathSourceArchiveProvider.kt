package org.javacs.kt.externalsources

import org.javacs.kt.CompilerClassPath
import java.nio.file.Path

class ClassPathSourceArchiveProvider(
    private val classPath: CompilerClassPath
) : SourceArchiveProvider {
    override fun fetchSourceArchive(compiledArchive: Path): Path? =
        classPath.classPath.firstOrNull { it.compiledJar == compiledArchive }?.sourceJar
}
