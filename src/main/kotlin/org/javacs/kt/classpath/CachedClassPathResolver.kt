package org.javacs.kt.classpath

import org.javacs.kt.LOG
import org.javacs.kt.getDB
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.nio.file.Paths

private const val MAX_PATH_LENGTH = 2047

private object ClassPathMetadataCache : IntIdTable() {
    val includesSources = bool("includessources")
    val buildFileVersion = long("buildfileversion").nullable()
}

private object ClassPathCacheEntry : IntIdTable() {
    val compiledJar = varchar("compiledjar", length = MAX_PATH_LENGTH)
    val sourceJar = varchar("sourcejar", length = MAX_PATH_LENGTH).nullable()
}

class ClassPathMetadataCacheEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ClassPathMetadataCacheEntity>(ClassPathMetadataCache)

    var includesSources by ClassPathMetadataCache.includesSources
    var buildFileVersion by ClassPathMetadataCache.buildFileVersion
}

class ClassPathCacheEntryEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ClassPathCacheEntryEntity>(ClassPathCacheEntry)

    var compiledJar by ClassPathCacheEntry.compiledJar
    var sourceJar by ClassPathCacheEntry.sourceJar
}

fun createCachedResolverTables() {
    transaction(getDB()) {
        SchemaUtils.createMissingTablesAndColumns(
            ClassPathMetadataCache, ClassPathCacheEntry
        )
    }
}

fun getCachedClasspath(path: Path): Set<ClassPathEntry> {
    if (!dependenciesChanged(path)) {
        LOG.info("Classpath has not changed. Fetching from cache")
        return cachedClassPathEntries
    }

    LOG.info("Cached classpath is outdated or not found. Resolving again")

    val newClasspath = getGradleClasspath(path)
    updateClasspathCache(path, newClasspath, false)

    return newClasspath
}

fun getCachedClasspathWithSources(path: Path): Set<ClassPathEntry> {
    val classpath = cachedClassPathMetadata
    if (classpath != null && !dependenciesChanged(path) && classpath.includesSources) return cachedClassPathEntries

    val newClasspath = getGradleClasspath(path)
    updateClasspathCache(path, newClasspath, true)

    return newClasspath
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
