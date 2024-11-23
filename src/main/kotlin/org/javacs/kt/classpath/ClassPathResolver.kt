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

fun defaultClassPathResolver(workspaceRoot: Path): ClassPathResolver {
    // Check for build.gradle or build.gradle.kts
    val buildGradleFile = getBuildGradleFile(workspaceRoot)
    if(buildGradleFile == null) throw RuntimeException("build.gradle file not found")

    val childResolver = GradleClassPathResolver(buildGradleFile)

    return CachedClassPathResolver(buildGradleFile, childResolver)
}

fun getClasspathOrEmpty(it: ClassPathResolver): Set<ClassPathEntry> {
    try {
        return it.classpath
    } catch (e: Exception) {
        LOG.warn("Could not resolve classpath: {}", e.message)
        return emptySet<ClassPathEntry>()
    }
}

data class ClassPathEntry(
    val compiledJar: Path,
    val sourceJar: Path? = null
)

/** A source for creating class paths */
interface ClassPathResolver {
    val classpath: Set<ClassPathEntry> // may throw exceptions
    val classpathWithSources: Set<ClassPathEntry> get() = classpath
}
