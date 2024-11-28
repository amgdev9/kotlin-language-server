package org.javacs.kt

import org.javacs.kt.util.AsyncExecutor
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.streams.asSequence

/**
 * Manages the class path (compiled JARs, etc), the Java source path
 * and the compiler. Note that Kotlin sources are stored in SourcePath.
 */
class CompilerClassPath: Closeable {
    private val javaSourcePath = mutableSetOf<Path>()
    val classPath = mutableSetOf<ClassPathEntry>()
    val outputDirectory: File = Files.createTempDirectory("klsBuildOutput").toFile()
    val javaHome: String? = System.getProperty("java.home", null)

    var compiler = Compiler(
        javaSourcePath,
        classPath.map { it.compiledJar }.toSet(),
        outputDirectory
    )
        private set

    private val async = AsyncExecutor()

    /** Updates and possibly reinstantiates the compiler using new paths. */
    private fun refresh(
        updateClassPath: Boolean = true,
        updateJavaSourcePath: Boolean = true
    ): Boolean {
        // TODO: Fetch class path concurrently (and asynchronously)
        var refreshCompiler = updateJavaSourcePath
        val projectClasspath = clientSession.projectClasspath

        if (updateClassPath) {
            if (projectClasspath.classPath != classPath) {
                synchronized(classPath) {
                    syncPaths(classPath, projectClasspath.classPath, "class path")
                }
                refreshCompiler = true
            }

            async.compute {
                val newClassPathWithSources = projectClasspath
                synchronized(classPath) {
                    syncPaths(classPath, newClassPathWithSources.classPath, "class path with sources")
                }
            }
        }

        if (refreshCompiler) {
            LOG.info("Reinstantiating compiler")
            compiler.close()
            compiler = Compiler(
                javaSourcePath,
                classPath.asSequence().map { it.compiledJar }.toSet(),
                outputDirectory
            )
            updateCompilerConfiguration()
        }

        return refreshCompiler
    }

    /** Synchronizes the given two path sets and logs the differences. */
    private fun <T> syncPaths(dest: MutableSet<T>, new: Set<T>, name: String) {
        val added = new - dest
        val removed = dest - new

        LOG.info("Adding {} files to {}", added.size, name)
        LOG.info("Removing {} files from {}", removed.size, name)

        dest.removeAll(removed)
        dest.addAll(added)
    }

    fun updateCompilerConfiguration() {
        compiler.updateConfiguration()
    }

    fun setupWorkspaceRoot(): Boolean {
        LOG.info("Searching for dependencies and Java sources...")

        javaSourcePath.addAll(findJavaSourceFiles(clientSession.projectClasspath.javaSourceDirs))

        return refresh()
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

        return refresh(
            updateClassPath = false,
            updateJavaSourcePath = true
        )
    }

    private fun isJavaSource(file: Path): Boolean = file.fileName.toString().endsWith(".java")

    override fun close() {
        compiler.close()
        outputDirectory.delete()
    }
}
