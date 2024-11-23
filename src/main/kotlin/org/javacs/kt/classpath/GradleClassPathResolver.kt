package org.javacs.kt.classpath

import org.javacs.kt.LOG
import org.javacs.kt.util.findCommandOnPath
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal class GradleClassPathResolver(private val path: Path): ClassPathResolver {
    private val projectDirectory: Path get() = path.parent

    override val classpath: Set<ClassPathEntry> get() {
        val classpath = readDependenciesViaGradleCLI(projectDirectory)
        if (classpath.isNotEmpty()) {
            LOG.info("Successfully resolved dependencies for '${projectDirectory.fileName}' using Gradle")
        }

        return classpath.asSequence().map { ClassPathEntry(it, null) }.toSet()
    }

    override val currentBuildFileVersion: Long get() = path.toFile().lastModified()
}

private fun readDependenciesViaGradleCLI(projectDirectory: Path): Set<Path> {
    LOG.info("Resolving dependencies for '{}' through Gradle's CLI using tasks {}...", projectDirectory.fileName, "kotlinLSPProjectDeps")

    val tmpScript = gradleScriptToTempFile("projectClassPathFinder.gradle").toPath().toAbsolutePath()
    val gradle = getGradleCommand(projectDirectory)

    val command = "$gradle -I $tmpScript kotlinLSPProjectDeps --console=plain"
    val dependencies = findGradleCLIDependencies(command, projectDirectory)

    if(dependencies != null) {
        LOG.debug("Classpath for task {}", dependencies)
    }

    Files.delete(tmpScript)

    return dependencies
        .orEmpty()
        .asSequence()
        .filter { it.toString().lowercase().endsWith(".jar") || Files.isDirectory(it) } // Some Gradle plugins seem to cause this to output POMs, therefore filter JARs
        .toSet()
}

private fun gradleScriptToTempFile(scriptName: String): File {
    val gradleConfigFile = File.createTempFile("classpath", ".gradle")
    LOG.debug("Creating temporary gradle file {}", gradleConfigFile.absolutePath)

    gradleConfigFile.bufferedWriter().use { configWriter ->
        GradleClassPathResolver::class.java.getResourceAsStream("/$scriptName")!!.bufferedReader().use { configReader ->
            configReader.copyTo(configWriter)
        }
    }

    return gradleConfigFile
}

private fun getGradleCommand(workspace: Path): Path {
    val wrapper = workspace.resolve("gradlew").toAbsolutePath()
    if (Files.isExecutable(wrapper)) return wrapper

    val parent = workspace.parent
    if (parent != null) return getGradleCommand(parent)

    val gradlePath = findCommandOnPath("gradle")
    if (gradlePath != null) return gradlePath

    throw RuntimeException("Could not find 'gradle' on PATH")
}

private fun findGradleCLIDependencies(command: String, projectDirectory: Path): Set<Path>? {
    val (result, errors) = execAndReadStdoutAndStderr(command, projectDirectory)

    if ("FAILURE: Build failed" in errors) {
        LOG.warn("Gradle task failed: {}", errors)
    } else {
        for (error in errors.lines()) {
            if ("ERROR: " in error) {
                LOG.warn("Gradle error: {}", error)
            }
        }
    }

    return parseGradleCLIDependencies(result)
}

private val artifactPattern by lazy { "kotlin-lsp-gradle (.+)(?:\r?\n)".toRegex() }

private fun parseGradleCLIDependencies(output: String): Set<Path>? {
    return artifactPattern.findAll(output)
        .mapNotNull { it.groups[1]?.value }
        .mapNotNull { Paths.get(it) }
        .toSet()
}

private fun execAndReadStdoutAndStderr(shellCommand: String, directory: Path): Pair<String, String> {
    val process = ProcessBuilder().command(shellCommand.split(" ")).directory(directory.toFile()).start()
    val stdout = StringBuilder()
    val stderr = StringBuilder()

    process.inputStream.bufferedReader().use { reader ->
        reader.forEachLine { stdout.append(it).appendLine() }
    }

    process.errorStream.bufferedReader().use { reader ->
        reader.forEachLine { stderr.append(it).appendLine() }
    }

    process.waitFor()

    return Pair(stdout.toString(), stderr.toString())
}
