package org.javacs.kt

import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.streams.asSequence

val javaHome: String? = System.getProperty("java.home", null)

/**
 * Manages the class path (compiled JARs, etc), the Java source path
 * and the compiler. Note that Kotlin sources are stored in SourcePath.
 */
class CompilerClassPath: Closeable {
    private val javaSourcePath = mutableSetOf<Path>()
    val outputDirectory: File = Files.createTempDirectory("klsBuildOutput").toFile()
    var compiler: Compiler? = null

    private fun buildCompiler(): Compiler {
        return Compiler(
            javaSourcePath,
            clientSession.projectClasspath.classPath.asSequence().map { it.compiledJar }.toSet(),
            outputDirectory
        )
    }

    fun setupWorkspaceRoot() {
        LOG.info("Searching for and Java sources...")
        javaSourcePath.addAll(findJavaSourceFiles(clientSession.projectClasspath.javaSourceDirs))
        compiler = buildCompiler()
    }

    private fun findJavaSourceFiles(javaSourceDirs: Set<Path>): Set<Path> {
        return javaSourceDirs.asSequence()
            .flatMap {
                Files.walk(it).filter { it.extension == "java" }.asSequence()
            }
            .toSet()
    }

    fun createdOnDisk(file: Path): Boolean {
        if (isJavaSource(file)) {
            javaSourcePath.add(file)
        }
        return changedOnDisk(file)
    }

    fun deletedOnDisk(file: Path): Boolean {
        if (isJavaSource(file)) {
            javaSourcePath.remove(file)
        }
        return changedOnDisk(file)
    }

    fun changedOnDisk(file: Path): Boolean {
        val javaSource = isJavaSource(file)
        if (!javaSource) return false

        LOG.info("Reinstantiating compiler")
        compiler?.close()
        compiler = buildCompiler()
        return true
    }

    private fun isJavaSource(file: Path): Boolean = file.fileName.toString().endsWith(".java")

    override fun close() {
        compiler?.close()
        outputDirectory.delete()
    }
}
