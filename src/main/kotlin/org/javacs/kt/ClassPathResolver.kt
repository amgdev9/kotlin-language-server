package org.javacs.kt

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString

data class ClassPathEntry(
    val compiledJar: Path,
    val sourceJar: Path? = null
)

data class ProjectClasspath(
    val classPath: Set<ClassPathEntry>,
    val javaSourceDirs: Set<Path>,
    val kotlinSourceDirs: Set<Path>,
)

fun loadClasspathFromDisk(rootPath: Path): ProjectClasspath {
    val path = rootPath.resolve(".klsp-classpath")
    val content = File(path.absolutePathString()).readText()

    val javaSourceDirs = mutableSetOf<Path>()
    val kotlinSourceDirs = mutableSetOf<Path>()
    val classpath = mutableSetOf<ClassPathEntry>()

    content.splitToSequence("\n").forEach {
        val words = it.split(" ")
        if(words.size != 2) return@forEach
        val (type, path) = words

        if(!Files.exists(Path.of(path))) return@forEach

        if(type == "kotlin-lsp-sourcedir-java") {
            javaSourceDirs.add(Paths.get(path))
        } else if(type == "kotlin-lsp-sourcedir-kotlin") {
            kotlinSourceDirs.add(Paths.get(path))
        } else if(type == "kotlin-lsp-gradle" && path.endsWith(".jar") || Files.isDirectory(Path.of(path))) {
            classpath.add(ClassPathEntry(Paths.get(path), null))    // TODO Add sources if available
        }
    }

    return ProjectClasspath(
        javaSourceDirs = javaSourceDirs,
        kotlinSourceDirs = kotlinSourceDirs,
        classPath = classpath
    )
}
