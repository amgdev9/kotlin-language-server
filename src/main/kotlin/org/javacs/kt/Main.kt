package org.javacs.kt

import org.eclipse.lsp4j.launch.LSPLauncher
import org.javacs.kt.lsp.KotlinLanguageServer
import java.util.concurrent.Executors

fun main(argv: Array<String>) {
    LOG.connectJavaUtilLogFrontend()

    val server = KotlinLanguageServer()
    val threads = Executors.newSingleThreadExecutor { Thread(it, "client") }
    val launcher = LSPLauncher.createServerLauncher(server, System.`in`, System.out, threads) { it }
    server.connect(launcher.remoteProxy)
    launcher.startListening()
}
