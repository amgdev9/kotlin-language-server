package org.javacs.kt

import java.util.concurrent.Executors
import org.eclipse.lsp4j.launch.LSPLauncher
import org.javacs.kt.lsp.KotlinLanguageServer
import java.io.InputStream
import org.javacs.kt.LOG
import kotlin.system.exitProcess

fun main(argv: Array<String>) {
    LOG.connectJavaUtilLogFrontend()

    val server = KotlinLanguageServer()
    val threads = Executors.newSingleThreadExecutor { Thread(it, "client") }
    val launcher = LSPLauncher.createServerLauncher(server, System.`in`, System.out, threads) { it }

    server.connect(launcher.remoteProxy)
    launcher.startListening()
}
