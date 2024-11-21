package org.javacs.kt

import java.util.concurrent.Executors
import org.eclipse.lsp4j.launch.LSPLauncher
import org.javacs.kt.lsp.KotlinLanguageServer
import org.javacs.kt.util.ExitingInputStream

fun main(argv: Array<String>) {
    LOG.connectJavaUtilLogFrontend()

    val server = KotlinLanguageServer()
    val threads = Executors.newSingleThreadExecutor { Thread(it, "client") }
    val launcher = LSPLauncher.createServerLauncher(server, ExitingInputStream(System.`in`), System.out, threads) { it }

    server.connect(launcher.remoteProxy)
    launcher.startListening()
}
