package org.javacs.kt.classpath

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.javacs.kt.util.userHome
import org.javacs.kt.LOG

/** Executes a script to determine the classpath */
internal class ScriptClassPathResolver(
    private val script: Path,
    private val workingDir: Path? = null
) : ClassPathResolver {
    override val classpath: Set<ClassPathEntry> get() {
        val workingDirectory = workingDir?.toFile() ?: script.toAbsolutePath().parent.toFile()
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

        /** The root directory for config files. */
        private val globalConfigRoot: Path =
            System.getenv("XDG_CONFIG_HOME")?.let { Paths.get(it) } ?: userHome.resolve(".config")

        /** Returns the ShellClassPathResolver for the global home directory shell script. */
        fun global(workingDir: Path?): ClassPathResolver {
            val path = globalConfigRoot.resolve("kotlin-language-server").resolve("classpath")
            if(!Files.exists(path)) return ClassPathResolver.empty

            return ScriptClassPathResolver(path, workingDir)
        }
    }
}
