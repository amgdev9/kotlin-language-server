package org.javacs.kt.classpath

import org.javacs.kt.LOG
import java.nio.file.Files
import java.nio.file.Path

fun getBuildGradleFile(workspaceRoot: Path): Path? {
    val buildGradle = workspaceRoot.resolve("build.gradle")
    if(Files.exists(buildGradle)) return buildGradle

    val buildGradleKts = workspaceRoot.resolve("build.gradle.kts")
    if(Files.exists(buildGradleKts)) return buildGradleKts

    return null
}

fun getClasspath(workspaceRoot: Path): GradleProjectInfo {
    // Check for build.gradle or build.gradle.kts
    val buildGradleFile = getBuildGradleFile(workspaceRoot)
    if(buildGradleFile == null) throw RuntimeException("build.gradle file not found")

    return getCachedClasspath(buildGradleFile)
}

fun getClasspathWithSources(workspaceRoot: Path): GradleProjectInfo {
    // Check for build.gradle or build.gradle.kts
    val buildGradleFile = getBuildGradleFile(workspaceRoot)
    if(buildGradleFile == null) throw RuntimeException("build.gradle file not found")

    return getCachedClasspathWithSources(buildGradleFile)
}

fun getClasspathOrEmpty(path: Path): GradleProjectInfo {
    try {
        return getClasspath(path)
    } catch (e: Exception) {
        LOG.info(e.stackTraceToString())
        LOG.warn("Could not resolve classpath: {}", e.message)
        return GradleProjectInfo(classPath = emptySet(), javaSourceDirs = emptySet(), kotlinSourceDirs = emptySet())
    }
}

data class ClassPathEntry(
    val compiledJar: Path,
    val sourceJar: Path? = null
)
