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
    val launcher = LSPLauncher.createServerLauncher(server, ExitingInputStream(System.`in`), System.out, threads) { it }

    server.connect(launcher.remoteProxy)
    launcher.startListening()
}

class ExitingInputStream(private val delegate: InputStream): InputStream() {
    override fun read(): Int = exitIfNegative { delegate.read() }

    override fun read(b: ByteArray): Int = exitIfNegative { delegate.read(b) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = exitIfNegative { delegate.read(b, off, len) }

    private fun exitIfNegative(call: () -> Int): Int {
        val result = call()

        if (result < 0) {
            LOG.info("System.in has closed, exiting")
            exitProcess(0)
        }

        return result
    }
}

