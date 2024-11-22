package org.javacs.kt.classpath

import org.javacs.kt.LOG
import org.jetbrains.exposed.sql.Database
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.FileSystems

fun defaultClassPathResolver(workspaceRoots: Collection<Path>, db: Database?): ClassPathResolver {
    val childResolver = WithStdlibResolver(
        ScriptClassPathResolver.global(workspaceRoots.firstOrNull())
            .or(workspaceRoots.asSequence().flatMap { workspaceResolvers(it) }.joined)
    ).or(BackupClassPathResolver)

    if(db != null) return CachedClassPathResolver(childResolver, db)
    return childResolver
}

/** Searches the workspace for all files that could provide classpath info. */
private fun workspaceResolvers(workspaceRoot: Path): Sequence<ClassPathResolver> {
    val ignored = ignoredPathPatterns(workspaceRoot, workspaceRoot.resolve(".gitignore"))
    return workspaceRoot.toFile()
        .walk()
        .onEnter { file -> ignored.none { it.matches(file.toPath()) } }
        .mapNotNull { asClassPathProvider(it.toPath()) }
        .toList()
        .asSequence()
}

/** Tries to read glob patterns from a gitignore. */
private fun ignoredPathPatterns(root: Path, gitignore: Path): List<PathMatcher> =
    gitignore.toFile()
        .takeIf { it.exists() }
        ?.readLines()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() && !it.startsWith("#") }
        ?.map { it.removeSuffix("/") }
        ?.let { it + listOf(
            // Patterns that are ignored by default
            ".git"
        ) }
        ?.mapNotNull { try {
            LOG.debug("Adding ignore pattern '{}' from {}", it, gitignore)
            FileSystems.getDefault().getPathMatcher("glob:$root**/$it")
        } catch (e: Exception) {
            LOG.warn("Did not recognize gitignore pattern: '{}' ({})", it, e.message)
            null
        } }
        ?: emptyList()

/** Tries to create a classpath resolver from a file using as many sources as possible */
private fun asClassPathProvider(path: Path): ClassPathResolver? =
    GradleClassPathResolver.maybeCreate(path)
        ?: ScriptClassPathResolver.maybeCreate(path)
