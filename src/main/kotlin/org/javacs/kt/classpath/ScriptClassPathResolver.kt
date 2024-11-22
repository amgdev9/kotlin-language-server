package org.javacs.kt.classpath

import org.javacs.kt.LOG
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Executes a script to determine the classpath */
internal class ScriptClassPathResolver(
    private val script: Path
) : ClassPathResolver {
    override val classpath: Set<ClassPathEntry> get() {
        val workingDirectory = script.toAbsolutePath().parent.toFile()
        val cmd = script.toString()
        LOG.info("Run {} in {}", cmd, workingDirectory)
        val process = ProcessBuilder(cmd).directory(workingDirectory).start()

        return process.inputStream.bufferedReader().readText()
            .split("\n")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { ClassPathEntry(Paths.get(it), null) }
            .toSet()
    }

    companion object {
        fun maybeCreate(file: Path): ScriptClassPathResolver? {
            if(file.fileName.toString().substringBeforeLast(".") != "kls-classpath") return null
            if(!Files.isExecutable(file)) {
                LOG.warn("Found classpath script $file that is NOT executable and therefore cannot be used. Perhaps you'd want to chmod +x it?")
                return null
            }

            return ScriptClassPathResolver(file)
        }
    }
}
