package org.javacs.kt.util

import org.javacs.kt.LOG
import java.nio.file.Paths
import java.nio.file.Path
import java.io.File

internal val userHome = Paths.get(System.getProperty("user.home"))

internal fun isOSWindows() = (File.separatorChar == '\\')

fun findCommandOnPath(fileName: String): Path? {
    for (dir in System.getenv("PATH").split(File.pathSeparator)) {
        val file = File(dir, fileName)

        if (file.isFile && file.canExecute()) {
            LOG.info("Found {} at {}", fileName, file.absolutePath)

            return Paths.get(file.absolutePath)
        }
    }

    return null
}
