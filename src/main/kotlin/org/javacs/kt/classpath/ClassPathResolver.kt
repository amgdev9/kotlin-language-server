package org.javacs.kt.classpath

import org.javacs.kt.LOG
import java.nio.file.Path

fun getClasspath(): GradleProjectInfo {
    return getCachedClasspath()
}

fun getClasspathWithSources(): GradleProjectInfo {
    return getCachedClasspathWithSources()
}

fun getClasspathOrEmpty(): GradleProjectInfo {
    try {
        return getClasspath()
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
