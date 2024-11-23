package org.javacs.kt.util

import org.javacs.kt.LOG
import java.io.PrintStream
import java.nio.file.Path
import java.nio.file.Paths

inline fun withCustomStdout(delegateOut: PrintStream, task: () -> Unit) {
    val actualOut = System.out
    System.setOut(delegateOut)
    task()
    System.setOut(actualOut)
}

fun winCompatiblePathOf(path: String): Path {
    return if (path[2] == ':' && path[0] == '/') {
        // Strip leading '/' when dealing with paths on Windows
        Paths.get(path.substring(1))
    } else {
        Paths.get(path)
    }
}

fun String.partitionAroundLast(separator: String): Pair<String, String> = lastIndexOf(separator)
    .let { Pair(substring(0, it), substring(it, length)) }

fun Path.replaceExtensionWith(newExtension: String): Path {
	val oldName = fileName.toString()
	val newName = oldName.substring(0, oldName.lastIndexOf(".")) + newExtension
	return resolveSibling(newName)
}

inline fun <T, C : Iterable<T>> C.onEachIndexed(transform: (index: Int, T) -> Unit): C = apply {
    var i = 0
    for (element in this) {
        transform(i, element)
        i++
    }
}

fun <T> noResult(message: String, result: T): T {
    LOG.info(message)
    return result
}

fun <T> emptyResult(message: String): List<T> = noResult(message, emptyList())

fun <T> nullResult(message: String): T? = noResult(message, null)
