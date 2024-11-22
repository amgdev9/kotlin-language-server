package org.javacs.kt.classpath

import org.javacs.kt.LOG

fun getClasspathOrEmpty(it: ClassPathResolver): Set<ClassPathEntry> {
    try {
        return it.classpath
    } catch (e: Exception) {
        LOG.warn("Could not resolve classpath: {}", e.message)
        return emptySet<ClassPathEntry>()
    }
}

/** A source for creating class paths */
interface ClassPathResolver {
    val classpath: Set<ClassPathEntry> // may throw exceptions
    val classpathWithSources: Set<ClassPathEntry> get() = classpath

    /**
     * This should return the current build file version.
     * It usually translates to the file's lastModified time.
     * Resolvers that don't have a build file use the default (i.e., 1).
     * We use 1, because this will prevent any attempt to cache non cacheable resolvers
     * (see [CachedClassPathResolver.dependenciesChanged]).
     */
    val currentBuildFileVersion: Long
        get() = 1L
}
