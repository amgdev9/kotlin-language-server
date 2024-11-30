package org.javacs.kt.util

import org.javacs.kt.LOG
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private var threadCount = 0

class Debouncer {
    private var pendingTask: Future<*>? = null
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(1) {
        Thread(it, "debounce${threadCount++}")
    }

    fun submitImmediately(task: (cancelCallback: () -> Boolean) -> Unit) {
        pendingTask?.cancel(false)
        val currentTaskRef = AtomicReference<Future<*>>()
        val currentTask = executor.submit { task { currentTaskRef.get()?.isCancelled == true } }
        currentTaskRef.set(currentTask)
        pendingTask = currentTask
    }

    fun schedule(task: (cancelCallback: () -> Boolean) -> Unit) {
        pendingTask?.cancel(false)
        val currentTaskRef = AtomicReference<Future<*>>()
        val currentTask = executor.schedule({ task { currentTaskRef.get()?.isCancelled == true } }, 250, TimeUnit.MILLISECONDS)
        currentTaskRef.set(currentTask)
        pendingTask = currentTask
    }

    fun shutdown() {
        executor.shutdown()
        LOG.info("Awaiting debouncer termination...")
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)
    }
}
