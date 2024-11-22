package org.javacs.kt.classpath

import org.javacs.kt.LOG
import java.nio.file.Path
import kotlin.math.max

fun getClasspathOrEmpty(it: ClassPathResolver): Set<ClassPathEntry> {
    try {
        return it.classpath
    } catch (e: Exception) {
        LOG.warn("Could not resolve classpath: {}", e.message)
        return emptySet<ClassPathEntry>()
    }
}

fun getBuildScriptClasspathOrEmpty(it: ClassPathResolver): Set<Path> {
    try {
        return it.buildScriptClasspath
    } catch (e: Exception) {
        LOG.warn("Could not resolve buildscript classpath: {}", e.message)
        return emptySet<Path>()
    }
}

/** A source for creating class paths */
interface ClassPathResolver {
    val classpath: Set<ClassPathEntry> // may throw exceptions
    val buildScriptClasspath: Set<Path>
        get() = emptySet<Path>()
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

    companion object {
        /** A default empty classpath implementation */
        val empty = object : ClassPathResolver {
            override val classpath = emptySet<ClassPathEntry>()
        }
    }
}

val Sequence<ClassPathResolver>.joined get() = fold(ClassPathResolver.empty) { accum, next -> accum + next }

/** Combines two classpath resolvers. */
operator fun ClassPathResolver.plus(other: ClassPathResolver): ClassPathResolver = UnionClassPathResolver(this, other)

/** The union of two class path resolvers. */
internal class UnionClassPathResolver(val lhs: ClassPathResolver, val rhs: ClassPathResolver) : ClassPathResolver {
    override val classpath get() = lhs.classpath + rhs.classpath
    override val buildScriptClasspath get() = lhs.buildScriptClasspath + rhs.buildScriptClasspath
    override val classpathWithSources get() = lhs.classpathWithSources + rhs.classpathWithSources
    override val currentBuildFileVersion: Long get() = max(lhs.currentBuildFileVersion, rhs.currentBuildFileVersion)
}
