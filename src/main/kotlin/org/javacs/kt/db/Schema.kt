package org.javacs.kt.db

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SchemaUtils

// Database metadata

object DatabaseMetadata : IntIdTable() {
    var version = integer("version")
}

class DatabaseMetadataEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DatabaseMetadataEntity>(DatabaseMetadata)

    var version by DatabaseMetadata.version
}

// Symbols index

const val MAX_FQNAME_LENGTH = 255
const val MAX_SHORT_NAME_LENGTH = 80
const val MAX_URI_LENGTH = 511

object Symbols : IntIdTable() {
    val fqName = varchar("fqname", length = MAX_FQNAME_LENGTH).index()
    val shortName = varchar("shortname", length = MAX_SHORT_NAME_LENGTH)
    val kind = integer("kind")
    val visibility = integer("visibility")
    val extensionReceiverType = varchar("extensionreceivertype", length = MAX_FQNAME_LENGTH).nullable()
    val location = optReference("location", Locations)
}

object Locations : IntIdTable() {
    val uri = varchar("uri", length = MAX_URI_LENGTH)
    val range = reference("range", Ranges)
}

object Ranges : IntIdTable() {
    val start = reference("start", Positions)
    val end = reference("end", Positions)
}

object Positions : IntIdTable() {
    val line = integer("line")
    val character = integer("character")
}

class SymbolEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<SymbolEntity>(Symbols)

    var fqName by Symbols.fqName
    var shortName by Symbols.shortName
    var kind by Symbols.kind
    var visibility by Symbols.visibility
    var extensionReceiverType by Symbols.extensionReceiverType
    var location by LocationEntity optionalReferencedOn Symbols.location
}

class LocationEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<LocationEntity>(Locations)

    var uri by Locations.uri
    var range by RangeEntity referencedOn Locations.range
}

class RangeEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<RangeEntity>(Ranges)

    var start by PositionEntity referencedOn Ranges.start
    var end by PositionEntity referencedOn Ranges.end
}

class PositionEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<PositionEntity>(Positions)

    var line by Positions.line
    var character by Positions.character
}

// Cached classpath
const val MAX_PATH_LENGTH = 2047

object ClassPathMetadataCache : IntIdTable() {
    val includesSources = bool("includessources")
    val buildFileVersion = long("buildfileversion").nullable()
}

object ClassPathCacheEntry : IntIdTable() {
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

fun setupTables() {
    SchemaUtils.createMissingTablesAndColumns(
        DatabaseMetadata,
        Symbols,
        Locations,
        Ranges,
        Positions,
        ClassPathMetadataCache,
        ClassPathCacheEntry
    )
}
