package org.javacs.kt.classpath

import org.jetbrains.exposed.sql.Database
import java.nio.file.Files
import java.nio.file.Path

fun getBuildGradleFile(workspaceRoot: Path): Path? {
    val buildGradle = workspaceRoot.resolve("build.gradle")
    if(Files.exists(buildGradle)) return buildGradle

    val buildGradleKts = workspaceRoot.resolve("build.gradle.kts")
    if(Files.exists(buildGradleKts)) return buildGradleKts

    return null
}

fun defaultClassPathResolver(workspaceRoot: Path, db: Database?): ClassPathResolver {
    // Check for build.gradle or build.gradle.kts
    val buildGradleFile = getBuildGradleFile(workspaceRoot)
    if(buildGradleFile == null) throw RuntimeException("build.gradle file not found")

    val childResolver = GradleClassPathResolver.maybeCreate(buildGradleFile)!!

    if (db != null) return CachedClassPathResolver(childResolver, db)
    return childResolver
}
