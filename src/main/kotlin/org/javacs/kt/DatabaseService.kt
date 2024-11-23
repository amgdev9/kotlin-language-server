package org.javacs.kt

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path

private object DatabaseMetadata : IntIdTable() {
    var version = integer("version")
}

class DatabaseMetadataEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DatabaseMetadataEntity>(DatabaseMetadata)

    var version by DatabaseMetadata.version
}

const val DB_VERSION = 1
const val DB_FILENAME = "kls_database.db"

private var db: Database? = null

fun getDB(): Database {
    if(db == null) throw RuntimeException("DB not set up")
    return db!!
}

fun setupDB(storagePath: Path) {
    db = getDbFromFile(storagePath)

    val currentVersion = transaction(db) {
        SchemaUtils.createMissingTablesAndColumns(DatabaseMetadata)

        DatabaseMetadataEntity.all().firstOrNull()?.version
    }

    if (currentVersion == DB_VERSION) {
        LOG.info("Database has the correct version $currentVersion and will be used as-is")
        return
    }

    LOG.info("Database has version $currentVersion != $DB_VERSION (the required version), therefore it will be rebuilt...")

    Files.deleteIfExists(storagePath.resolve(DB_FILENAME))
    db = getDbFromFile(storagePath)

    transaction(db) {
        SchemaUtils.createMissingTablesAndColumns(DatabaseMetadata)

        DatabaseMetadata.deleteAll()
        DatabaseMetadata.insert { it[version] = DB_VERSION }
    }
}

private fun getDbFromFile(storagePath: Path): Database {
    if (!Files.isDirectory(storagePath)) {
        throw RuntimeException("DB storage path is not a folder")
    }

    return Database.connect("jdbc:sqlite:${storagePath.resolve(DB_FILENAME)}")
}
