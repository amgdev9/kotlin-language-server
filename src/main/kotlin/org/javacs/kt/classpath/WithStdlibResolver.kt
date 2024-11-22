package org.javacs.kt.classpath

import org.javacs.kt.LOG
import org.javacs.kt.util.findCommandOnPath
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.function.BiPredicate

/** A classpath resolver that ensures another resolver contains the stdlib */
internal class WithStdlibResolver(private val wrapped: ClassPathResolver) : ClassPathResolver {
    override val classpath: Set<ClassPathEntry> get() = wrapWithStdlibEntries(wrapped.classpath)
    override val buildScriptClasspath: Set<Path> get() = wrapWithStdlib(wrapped.buildScriptClasspath)
    override val classpathWithSources: Set<ClassPathEntry> get() = wrapWithStdlibEntries(wrapped.classpathWithSources)
    override val currentBuildFileVersion: Long get() = wrapped.currentBuildFileVersion
}

private fun wrapWithStdlibEntries(paths: Set<ClassPathEntry>): Set<ClassPathEntry> {
    return wrapWithStdlib(paths.asSequence().map { it.compiledJar }.toSet()).asSequence().map {
        ClassPathEntry(
            it,
            paths.find { it1 -> it1.compiledJar == it }?.sourceJar
        )
    }.toSet()
}

private fun isStdlib(it: Path): Boolean {
    val pathString = it.toString()
    return pathString.contains("kotlin-stdlib") && !pathString.contains("kotlin-stdlib-common")
}

private fun wrapWithStdlib(paths: Set<Path>): Set<Path> {
    // Ensure that there is exactly one kotlin-stdlib present, and/or exactly one of kotlin-stdlib-common, -jdk8, etc.
    val linkedStdLibs = paths.filter(::isStdlib)
        .mapNotNull { StdLibItem.from(it) }
        .groupBy { it.key }
        .map { candidates ->
            // For each "kotlin-stdlib-blah", use the newest.  This may not be correct behavior if the project has lots of
            // conflicting dependencies, but in general should get enough of the stdlib loaded that we can display errors

            candidates.value.sortedWith(
                compareByDescending<StdLibItem> { it.major } then
                    compareByDescending { it.minor } then
                    compareByDescending { it.patch }
            ).first().path
        }

    val stdlibs = linkedStdLibs.ifEmpty {
        findKotlinStdlib()?.let { listOf(it) } ?: listOf()
    }

    return paths.filterNot(::isStdlib).union(stdlibs)
}

private data class StdLibItem(
    val key: String,
    val major: Int,
    val minor: Int,
    val patch: Int,
    val path: Path
) {
    companion object {
        // Matches names like: "kotlin-stdlib-jdk7-1.2.51.jar"
        val parser = Regex("""(kotlin-stdlib(-[^-]+)?)(?:-(\d+)\.(\d+)\.(\d+))?\.jar""")

        fun from(path: Path) : StdLibItem? {
            return parser.matchEntire(path.fileName.toString())?.let { match ->
                StdLibItem(
                    key = match.groups[1]?.value ?: match.groups[0]?.value!!,
                    major = match.groups[3]?.value?.toInt() ?: 0,
                    minor = match.groups[4]?.value?.toInt() ?: 0,
                    patch = match.groups[5]?.value?.toInt() ?: 0,
                    path = path
                )
            }
        }
    }
}

private fun findKotlinStdlib(): Path? =
    findKotlinCliCompilerLibrary("kotlin-stdlib")
        ?: findLocalArtifact("org.jetbrains.kotlin", "kotlin-stdlib")
        ?: findAlternativeLibraryLocation("kotlin-stdlib")

private fun findLocalArtifact(group: String, artifact: String) =
    tryResolving("$artifact using Gradle") { tryFindingLocalArtifactUsing(group, artifact, findLocalArtifactDirUsingGradle(group, artifact)) }

private fun tryFindingLocalArtifactUsing(group: String, artifact: String, artifactDirResolution: LocalArtifactDirectoryResolution): Path? {
    val isCorrectArtifact = BiPredicate<Path, BasicFileAttributes> { file, _ ->
        val name = file.fileName.toString()
        when (artifactDirResolution.buildTool) {
            "Maven" -> {
                val version = file.parent.fileName.toString()
                val expected = "${artifact}-${version}.jar"
                name == expected
            }
            else -> name.startsWith(artifact) && ("-sources" !in name) && name.endsWith(".jar")
        }
    }
    return Files.list(artifactDirResolution.artifactDir)
        .sorted(::compareVersions)
        .findFirst()
        .orElse(null)
        ?.let {
            Files.find(artifactDirResolution.artifactDir, 3, isCorrectArtifact)
                .findFirst()
                .orElse(null)
        }
}

private data class LocalArtifactDirectoryResolution(val artifactDir: Path?, val buildTool: String)

/** Tries to find the Kotlin command line compiler's standard library. */
private fun findKotlinCliCompilerLibrary(name: String): Path? =
    findCommandOnPath("kotlinc")
        ?.toRealPath()
        ?.parent // bin
        ?.parent // libexec or "top-level" dir
        ?.let {
            // either in libexec or a top-level directory (that may contain libexec, or just a lib-directory directly)
            val possibleLibDir = it.resolve("lib")
            if (Files.exists(possibleLibDir)) {
                possibleLibDir
            } else {
                it.resolve("libexec").resolve("lib")
            }
        }
        ?.takeIf { Files.exists(it) }
        ?.let(Files::list)
        ?.filter { it.fileName.toString() == "$name.jar" }
        ?.findFirst()
        ?.orElse(null)
        ?.also {
            LOG.info("Found Kotlin CLI compiler library $name at $it")
        }

// alternative library locations like for snap
private fun findAlternativeLibraryLocation(name: String): Path? =
    Paths.get("/snap/kotlin/current/lib/${name}.jar").existsOrNull()

private fun Path.existsOrNull() =
    if (Files.exists(this)) this else null

private fun findLocalArtifactDirUsingGradle(group: String, artifact: String) =
    LocalArtifactDirectoryResolution(gradleCaches
        ?.resolve(group)
        ?.resolve(artifact)
        ?.existsOrNull(), "Gradle")

// TODO: Resolve the gradleCaches dynamically instead of hardcoding this path
private val gradleCaches by lazy {
    gradleHome.resolve("caches")
        .resolveStartingWith("modules")
        .resolveStartingWith("files")
}

private fun Path.resolveStartingWith(prefix: String) = Files.list(this).filter { it.fileName.toString().startsWith(prefix) }.findFirst().orElse(null)

private fun compareVersions(left: Path, right: Path): Int {
    val leftVersion = extractVersion(left)
    val rightVersion = extractVersion(right)

    for (i in 0 until leftVersion.size.coerceAtMost(rightVersion.size)) {
        val leftRev = leftVersion[i].reversed()
        val rightRev = rightVersion[i].reversed()
        val compare = leftRev.compareTo(rightRev)
        if (compare != 0)
            return -compare
    }

    return -leftVersion.size.compareTo(rightVersion.size)
}

private fun extractVersion(artifactVersionDir: Path): List<String> {
    return artifactVersionDir.toString().split(".")
}

private inline fun <T> tryResolving(what: String, resolver: () -> T?): T? {
    try {
        val resolved = resolver()
        if (resolved == null) {
            LOG.info("Could not resolve {} as it is null", what)
            return null
        }
        LOG.info("Successfully resolved {} to {}", what, resolved)
        return resolved
    } catch (e: Exception) {
        LOG.info("Could not resolve {}: {}", what, e.message)
        return null
    }
}
