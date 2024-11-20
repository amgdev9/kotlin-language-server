package org.javacs.kt

import java.util.concurrent.Executors
import org.eclipse.lsp4j.launch.LSPLauncher
import org.javacs.kt.lsp.KotlinLanguageServer
import org.javacs.kt.util.ExitingInputStream
import org.javacs.kt.util.tcpStartServer
import org.javacs.kt.util.tcpConnectToClient

fun main(argv: Array<String>) {
    // Redirect java.util.logging calls (e.g. from LSP4J)
    LOG.connectJULFrontend()

    val server = KotlinLanguageServer()
    val threads = Executors.newSingleThreadExecutor { Thread(it, "client") }
    val launcher = LSPLauncher.createServerLauncher(server, ExitingInputStream(System.`in`), System.out, threads) { it }

    server.connect(launcher.remoteProxy)
    launcher.startListening()
}
