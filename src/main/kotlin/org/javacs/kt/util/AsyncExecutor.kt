package org.javacs.kt.util

import org.javacs.kt.LOG
import java.util.function.Supplier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AsyncExecutor(name: String) {
	private val workerThread = Executors.newSingleThreadExecutor { Thread(it, name) }

    fun execute(task: () -> Unit) =
		CompletableFuture.runAsync(Runnable(task), workerThread)

	fun <R> compute(task: () -> R) =
		CompletableFuture.supplyAsync(Supplier(task), workerThread)

	fun shutdown() {
		workerThread.shutdown()
		LOG.info("Awaiting async termination...")
		workerThread.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)
	}
}
