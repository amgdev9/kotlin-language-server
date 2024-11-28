package org.javacs.kt.index

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path

fun setupIndexDB(storagePath: Path): Database {
    val db = Database.connect("jdbc:sqlite:${storagePath.resolve(".klsp-index")}")

    transaction(db) {
        setupTables()
    }
    
    return db
}
