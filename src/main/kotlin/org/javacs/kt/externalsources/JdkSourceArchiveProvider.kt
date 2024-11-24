package org.javacs.kt.externalsources

import org.javacs.kt.clientSession
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

class JdkSourceArchiveProvider : SourceArchiveProvider {

    /**
     * Checks if the given path is inside the JDK. If it is, we return the corresponding source zip.
     * Note that this method currently doesn't take into the account the JDK version, which means JDK source code
     * is only available for JDK 9+ builds.
     * TODO: improve this resolution logic to work for older JDK versions as well.
     */
    override fun fetchSourceArchive(compiledArchive: Path): Path? {
        clientSession.classPath.javaHome?.let {
            val javaHomePath = File(it).toPath()
            if (compiledArchive == javaHomePath) {
                return Paths.get(compiledArchive.toString(), "lib", "src.zip")
            }
        }
        return null
    }
}
