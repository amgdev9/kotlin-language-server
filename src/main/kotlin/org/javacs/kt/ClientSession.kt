package org.javacs.kt

import org.jetbrains.exposed.sql.Database

private var CLIENT: ClientSession? = null
private val clientLock = Any()

data class ClientSession(val db: Database)

var clientSession: ClientSession
    get() = synchronized(clientLock) {
        CLIENT ?: throw RuntimeException("Client not connected!")
    }
    set(value) = synchronized(clientLock) {
        CLIENT = value
    }