package org.javacs.kt.db

import org.javacs.kt.LOG
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path

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
        setupTables()

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
        setupTables()

        DatabaseMetadata.deleteAll()
        DatabaseMetadata.insert { it[version] = DB_VERSION }
    }
}

private fun getDbFromFile(storagePath: Path): Database {
    return Database.connect("jdbc:sqlite:${storagePath.resolve(DB_FILENAME)}")
}
