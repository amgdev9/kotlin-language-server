package org.javacs.kt.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path

const val DB_FILENAME = "kls_database.db"

fun setupDB(storagePath: Path): Database {
    val db = Database.connect("jdbc:sqlite:${storagePath.resolve(DB_FILENAME)}")

    transaction(db) {
        setupTables()
    }
    
    return db
}

