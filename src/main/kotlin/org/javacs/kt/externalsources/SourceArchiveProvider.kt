package org.javacs.kt.externalsources

import org.javacs.kt.clientSession
import java.nio.file.Path

fun fetchCompositeSourceArchive(compiledArchive: Path): Path? {
    return fetchJdkSourceArchive(compiledArchive) ?: clientSession.classPath.classPath.firstOrNull { it.compiledJar == compiledArchive }?.sourceJar
}
