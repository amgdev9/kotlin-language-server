package org.javacs.kt.externalsources

import org.javacs.kt.clientSession
import java.nio.file.Path

class ClassPathSourceArchiveProvider : SourceArchiveProvider {
    override fun fetchSourceArchive(compiledArchive: Path): Path? =
        clientSession.classPath.classPath.firstOrNull { it.compiledJar == compiledArchive }?.sourceJar
}
