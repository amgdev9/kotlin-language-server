package org.javacs.kt

import org.eclipse.lsp4j.services.LanguageClient
import org.javacs.kt.util.TemporaryFolder
import org.jetbrains.exposed.sql.Database
import java.nio.file.Path

// For now the LSP handles 1 client at a time
private var CLIENT_SESSION: ClientSession? = null

data class ClientSession(
    val db: Database,
    val rootPath: Path,
    val client: LanguageClient,
    val classPath: CompilerClassPath,
    val tempFolder: TemporaryFolder,
    val decompilerOutputDir: Path,
    val sourcePath: SourcePath,
    val sourceFiles: SourceFiles,
    val config: Configuration
)

var clientSession: ClientSession
    get() = CLIENT_SESSION ?: throw RuntimeException("Client not connected!")

    set(value) {
        if(CLIENT_SESSION != null) {
            throw RuntimeException("This LSP only supports 1 client at a time")
        }
        CLIENT_SESSION = value
    }
