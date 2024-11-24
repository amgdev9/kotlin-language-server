package org.javacs.kt

import org.eclipse.lsp4j.services.LanguageClient
import org.jetbrains.exposed.sql.Database

// For now the LSP handles 1 client at a time
private var CLIENT_SESSION: ClientSession? = null

data class ClientSession(val db: Database, val client: LanguageClient)

var clientSession: ClientSession
    get() = CLIENT_SESSION ?: throw RuntimeException("Client not connected!")

    set(value) {
        if(CLIENT_SESSION != null) {
            throw RuntimeException("This LSP only supports 1 client at a time")
        }
        CLIENT_SESSION = value
    }