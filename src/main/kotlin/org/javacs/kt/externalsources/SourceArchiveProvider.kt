package org.javacs.kt.externalsources

import java.nio.file.Path

fun fetchCompositeSourceArchive(compiledArchive: Path): Path? {
    return fetchJdkSourceArchive(compiledArchive) ?: fetchClasspathSourceArchive(compiledArchive)
}
