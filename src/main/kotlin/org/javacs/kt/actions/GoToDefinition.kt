package org.javacs.kt.actions

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import org.javacs.kt.*
import org.javacs.kt.externalsources.KlsURI
import org.javacs.kt.externalsources.classContentOf
import org.javacs.kt.externalsources.toKlsURI
import org.javacs.kt.util.parseURI
import org.javacs.kt.util.partitionAroundLast
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

private val cachedTempFiles = mutableMapOf<KlsURI, Path>()
private val definitionPattern = Regex("(?:class|interface|object|fun)\\s+(\\w+)")

fun goToDefinition(file: CompiledFile, cursor: Int): Location? {
    val (_, target) = file.referenceExpressionAtPoint(cursor) ?: return null

    LOG.info("Found declaration descriptor {}", target)
    var destination = location(target)
    val psi = target.findPsi()

    if (psi is KtNamedDeclaration) {
        destination = psi.nameIdentifier?.let(::location) ?: destination
    }

    if (destination != null) {
        val rawClassURI = destination.uri

        if (isInsideArchive(rawClassURI)) {
            parseURI(rawClassURI).toKlsURI()?.let { klsURI ->
                val (klsSourceURI, content) = classContentOf(klsURI)

                val tmpFile = cachedTempFiles[klsSourceURI] ?: run {
                    val name = klsSourceURI.fileName.partitionAroundLast(".").first
                    val extensionWithoutDot = klsSourceURI.fileExtension
                    val extension = if (extensionWithoutDot != null) ".$extensionWithoutDot" else ""
                    clientSession.tempFolder.createTempFile(name, extension)
                        .also {
                            it.toFile().writeText(content)
                            cachedTempFiles[klsSourceURI] = it
                        }
                }

                destination.uri =   tmpFile.toUri().toString()

                if (destination.range.isZero) {
                    // Try to find the definition inside the source directly
                    val name = when (target) {
                        is ConstructorDescriptor -> target.constructedClass.name.toString()
                        else -> target.name.toString()
                    }
                    definitionPattern.findAll(content)
                        .map { it.groups[1]!! }
                        .find { it.value == name }?.range
                        ?.let { destination.range = Range(position(content, it.first), position(content, it.last)) }
                }
            }
        }
    }

    return destination
}

private fun isInsideArchive(uri: String) =
    uri.contains(".jar!") || uri.contains(".zip!") || javaHome?.let {
        Paths.get(parseURI(uri)).toString().startsWith(File(it).path)
    } == true
