package org.javacs.kt.classpath

import org.javacs.kt.LOG
import org.javacs.kt.util.findCommandOnPath
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class GradleProjectInfo(
    val classPath: Set<ClassPathEntry>,
    val javaSourceDirs: Set<Path>,
    val kotlinSourceDirs: Set<Path>,
)

fun getGradleProjectInfo(path: Path): GradleProjectInfo {
    val projectDirectory = path.parent

    val projectInfo = readDependenciesViaGradleCLI(projectDirectory)
    LOG.info("Resolved dependencies for '${projectDirectory.fileName}' using Gradle")

    return projectInfo
}

fun getGradleCurrentBuildFileVersion(path: Path) = path.toFile().lastModified()

private fun readDependenciesViaGradleCLI(projectDirectory: Path): GradleProjectInfo {
    LOG.info(
        "Resolving dependencies for '{}' through Gradle's CLI using tasks {}...",
        projectDirectory.fileName,
        "kotlinLSPProjectDeps"
    )

    val tmpScript = gradleScriptToTempFile("projectClassPathFinder.gradle").toPath().toAbsolutePath()
    val gradle = getGradleCommand(projectDirectory)

    val command = "$gradle -I $tmpScript kotlinLSPProjectDeps --console=plain"
    val dependencies = findGradleCLIDependencies(command, projectDirectory)

    LOG.debug("Classpath for task {}", dependencies)

    Files.delete(tmpScript)

    return dependencies
}

private fun gradleScriptToTempFile(scriptName: String): File {
    val gradleConfigFile = File.createTempFile("classpath", ".gradle")
    LOG.debug("Creating temporary gradle file {}", gradleConfigFile.absolutePath)

    gradleConfigFile.bufferedWriter().use { configWriter ->
        object {}.javaClass.getResourceAsStream("/$scriptName")!!.bufferedReader().use { configReader ->
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

private fun findGradleCLIDependencies(command: String, projectDirectory: Path): GradleProjectInfo {
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

private fun parseGradleCLIDependencies(output: String): GradleProjectInfo {
    val javaSourceDirs = mutableSetOf<Path>()
    val kotlinSourceDirs = mutableSetOf<Path>()
    val classpath = mutableSetOf<ClassPathEntry>()

    output.splitToSequence("\n").forEach {
        val words = it.split(" ")
        if(words.size != 2) return@forEach
        val (type, path) = words

        if(type == "kotlin-lsp-sourcedir-java") {
            javaSourceDirs.add(Paths.get(path))
        } else if(type == "kotlin-lsp-sourcedir-kotlin") {
            kotlinSourceDirs.add(Paths.get(path))
        } else if(type == "kotlin-lsp-gradle" && path.endsWith(".jar")) {
            classpath.add(ClassPathEntry(Paths.get(path), null))
        }
    }

    return GradleProjectInfo(
        javaSourceDirs = javaSourceDirs,
        kotlinSourceDirs = kotlinSourceDirs,
        classPath = classpath
    )
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
