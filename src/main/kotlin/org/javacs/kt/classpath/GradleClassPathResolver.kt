package org.javacs.kt.classpath

import org.javacs.kt.LOG
import org.javacs.kt.util.findCommandOnPath
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.javacs.kt.util.userHome

private fun createPathOrNull(envVar: String): Path? = System.getenv(envVar)?.let(Paths::get)

internal val gradleHome = createPathOrNull("GRADLE_USER_HOME") ?: userHome.resolve(".gradle")

internal class GradleClassPathResolver(private val path: Path, private val includeKotlinDSL: Boolean): ClassPathResolver {
    private val projectDirectory: Path get() = path.parent

    override val classpath: Set<ClassPathEntry> get() {
        val scripts = listOf("projectClassPathFinder.gradle")
        val tasks = listOf("kotlinLSPProjectDeps")

        return readDependenciesViaGradleCLI(projectDirectory, scripts, tasks)
            .apply { if (isNotEmpty()) LOG.info("Successfully resolved dependencies for '${projectDirectory.fileName}' using Gradle") }
            .map { ClassPathEntry(it, null) }.toSet()
    }
    override val buildScriptClasspath: Set<Path> get() {
        if (!includeKotlinDSL) return emptySet()

        val scripts = listOf("kotlinDSLClassPathFinder.gradle")
        val tasks = listOf("kotlinLSPKotlinDSLDeps")

        val classpath = readDependenciesViaGradleCLI(projectDirectory, scripts, tasks)
        if (classpath.isNotEmpty()) {
            LOG.info("Successfully resolved build script dependencies for '${projectDirectory.fileName}' using Gradle")
        }
        return classpath
    }

    override val currentBuildFileVersion: Long get() = path.toFile().lastModified()

    companion object {
        fun maybeCreate(file: Path): GradleClassPathResolver? {
            if(!file.endsWith("build.gradle") && !file.endsWith("build.gradle.kts")) return null
            return GradleClassPathResolver(file, includeKotlinDSL = file.toString().endsWith(".kts"))
        }
    }
}

private fun gradleScriptToTempFile(scriptName: String): File {
    val gradleConfigFile = File.createTempFile("classpath", ".gradle")

    LOG.debug("Creating temporary gradle file {}", gradleConfigFile.absolutePath)

    gradleConfigFile.bufferedWriter().use { configWriter ->
        GradleClassPathResolver::class.java.getResourceAsStream("/$scriptName").bufferedReader().use { configReader ->
            configReader.copyTo(configWriter)
        }
    }

    return gradleConfigFile
}

private fun getGradleCommand(workspace: Path): Path {
    val wrapper = workspace.resolve("gradlew").toAbsolutePath()
    if (Files.isExecutable(wrapper)) {
        return wrapper
    }

    return workspace.parent?.let(::getGradleCommand)
        ?: findCommandOnPath("gradle")
        ?: throw RuntimeException("Could not find 'gradle' on PATH")
}

private fun readDependenciesViaGradleCLI(projectDirectory: Path, gradleScripts: List<String>, gradleTasks: List<String>): Set<Path> {
    LOG.info("Resolving dependencies for '{}' through Gradle's CLI using tasks {}...", projectDirectory.fileName, gradleTasks)

    val tmpScripts = gradleScripts.map { gradleScriptToTempFile(it).toPath().toAbsolutePath() }
    val gradle = getGradleCommand(projectDirectory)

    val command = listOf(gradle.toString()) + tmpScripts.flatMap { listOf("-I", it.toString()) } + gradleTasks + listOf("--console=plain")
    val dependencies = findGradleCLIDependencies(command, projectDirectory)
        ?.also { LOG.debug("Classpath for task {}", it) }
        .orEmpty()
        .filter { it.toString().lowercase().endsWith(".jar") || Files.isDirectory(it) } // Some Gradle plugins seem to cause this to output POMs, therefore filter JARs
        .toSet()

    tmpScripts.forEach(Files::delete)
    return dependencies
}

private fun findGradleCLIDependencies(command: List<String>, projectDirectory: Path): Set<Path>? {
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
    LOG.debug(output)
    val artifacts = artifactPattern.findAll(output)
        .mapNotNull { Paths.get(it.groups[1]?.value) }
    return artifacts.toSet()
}

private fun execAndReadStdoutAndStderr(shellCommand: List<String>, directory: Path): Pair<String, String> {
    val process = ProcessBuilder(shellCommand).directory(directory.toFile()).start()
    val stdout = process.inputStream
    val stderr = process.errorStream
    var output = ""
    var errors = ""
    val outputThread = Thread { stdout.bufferedReader().use { output += it.readText() } }
    val errorsThread = Thread { stderr.bufferedReader().use { errors += it.readText() } }
    outputThread.start()
    errorsThread.start()
    outputThread.join()
    errorsThread.join()
    return Pair(output, errors)
}
