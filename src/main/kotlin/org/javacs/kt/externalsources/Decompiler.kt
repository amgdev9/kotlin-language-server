package org.javacs.kt.externalsources

import java.nio.file.Path
import java.nio.file.Files
import org.javacs.kt.util.replaceExtensionWith
import org.javacs.kt.util.withCustomStdout
import org.javacs.kt.LOG
import org.javacs.kt.clientSession
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler

fun decompileClass(compiledClass: Path) = decompile(compiledClass, ".java")

fun decompile(compiledClassOrJar: Path, newFileExtension: String): Path {
	val outputDir = clientSession.decompilerOutputDir
	invokeDecompiler(compiledClassOrJar, outputDir)
	val srcOutName = compiledClassOrJar.fileName.replaceExtensionWith(newFileExtension)
	val srcOutPath = outputDir.resolve(srcOutName)

	if (!Files.exists(srcOutPath)) {
		throw RuntimeException("Could not decompile ${compiledClassOrJar.fileName}: Fernflower did not generate sources at ${srcOutPath.fileName}")
	}

	return srcOutPath
}

private fun invokeDecompiler(input: Path, output: Path) {
	LOG.info("Decompiling {} using Fernflower...", input.fileName)
	withCustomStdout(LOG.outStream) {
		ConsoleDecompiler.main(arrayOf(input.toString(), output.toString()))
	}
}

fun createDecompilerOutputDirectory(): Path {
	val out = Files.createTempDirectory("fernflowerOut")
	Runtime.getRuntime().addShutdownHook(Thread {
		// Deletes the output directory and all contained (decompiled)
		// JARs when the JVM terminates
		out.toFile().deleteRecursively()
	})
	return out
}
