package org.javacs.kt.classpath

import org.javacs.kt.db.ClassPathCacheEntry
import org.javacs.kt.db.ClassPathCacheEntryEntity
import org.javacs.kt.db.ClassPathMetadataCache
import org.javacs.kt.db.ClassPathMetadataCacheEntity
import org.javacs.kt.db.getDB
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.nio.file.Paths

fun getCachedClasspath(path: Path): GradleProjectInfo {
    return getGradleProjectInfo(path)

    // TODO Implement caching for source files paths
    /*if (!dependenciesChanged(path)) {
        LOG.info("Classpath has not changed. Fetching from cache")
        return cachedClassPathEntries
    }

    LOG.info("Cached classpath is outdated or not found. Resolving again")

    val newClasspath = getGradleProjectInfo(path)
    updateClasspathCache(path, newClasspath, false)

    return newClasspath*/
}

fun getCachedClasspathWithSources(path: Path): GradleProjectInfo {
    return getGradleProjectInfo(path)

    // TODO Implement caching for source files paths
    /*val classpath = cachedClassPathMetadata
    if (classpath != null && !dependenciesChanged(path) && classpath.includesSources) return cachedClassPathEntries

    val newClasspath = getGradleProjectInfo(path)
    updateClasspathCache(path, newClasspath, true)

    return newClasspath*/
}

private var cachedClassPathEntries: Set<ClassPathEntry>
    get() = transaction(getDB()) {
        ClassPathCacheEntryEntity.all().map {
            ClassPathEntry(
                compiledJar = Paths.get(it.compiledJar),
                sourceJar = it.sourceJar?.let(Paths::get)
            )
        }.toSet()
    }
    set(newEntries) = transaction(getDB()) {
        ClassPathCacheEntry.deleteAll()
        newEntries.map {
            ClassPathCacheEntryEntity.new {
                compiledJar = it.compiledJar.toString()
                sourceJar = it.sourceJar?.toString()
            }
        }
    }

private var cachedClassPathMetadata
    get() = transaction(getDB()) {
        ClassPathMetadataCacheEntity.all().map {
            ClasspathMetadata(
                includesSources = it.includesSources,
                buildFileVersion = it.buildFileVersion
            )
        }.firstOrNull()
    }
    set(newClassPathMetadata) = transaction(getDB()) {
        ClassPathMetadataCache.deleteAll()
        val newClassPathMetadataRow = newClassPathMetadata ?: ClasspathMetadata()
        ClassPathMetadataCacheEntity.new {
            includesSources = newClassPathMetadataRow.includesSources
            buildFileVersion = newClassPathMetadataRow.buildFileVersion
        }
    }

private fun updateClasspathCache(path: Path, newClasspathEntries: Set<ClassPathEntry>, includesSources: Boolean) {
    transaction(getDB()) {
        cachedClassPathEntries = newClasspathEntries
        cachedClassPathMetadata = cachedClassPathMetadata?.copy(
            includesSources = includesSources,
            buildFileVersion = getGradleCurrentBuildFileVersion(path)
        ) ?: ClasspathMetadata()
    }
}

private fun dependenciesChanged(path: Path): Boolean {
    return (cachedClassPathMetadata?.buildFileVersion ?: 0) < getGradleCurrentBuildFileVersion(path)
}

private data class ClasspathMetadata(
    val includesSources: Boolean = false,
    val buildFileVersion: Long? = null
)
