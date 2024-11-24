package org.javacs.kt.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path

const val DB_VERSION = 1
const val DB_FILENAME = "kls_database.db"

fun setupDB(storagePath: Path): Database {
    val db = getDbFromFile(storagePath)

    val currentVersion = transaction(db) {
        setupTables()
        DatabaseMetadataEntity.all().firstOrNull()?.version
    }

    if (currentVersion == DB_VERSION) return db

    Files.deleteIfExists(storagePath.resolve(DB_FILENAME))
    val newDb = getDbFromFile(storagePath)

    transaction(newDb) {
        setupTables()
        DatabaseMetadata.insert { it[version] = DB_VERSION }
    }

    return newDb
}

private fun getDbFromFile(storagePath: Path): Database {
    return Database.connect("jdbc:sqlite:${storagePath.resolve(DB_FILENAME)}")
}
