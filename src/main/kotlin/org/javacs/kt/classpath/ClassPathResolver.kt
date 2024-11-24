package org.javacs.kt.classpath

import org.javacs.kt.LOG
import java.nio.file.Path

fun getClasspath(workspaceRoot: Path): GradleProjectInfo {
    return getCachedClasspath(workspaceRoot)
}

fun getClasspathWithSources(workspaceRoot: Path): GradleProjectInfo {
    return getCachedClasspathWithSources(workspaceRoot)
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
