val exposedVersion = "0.37.3"
val lsp4jVersion = "0.21.2"

plugins {
    kotlin("jvm") version "2.0.20"
    id("application")
}

group = "org.javacs.kt"
version = "1.0.0"
val mainClassName = "org.javacs.kt.MainKt"

repositories {
    mavenCentral()
    maven(url = "https://repo.gradle.org/gradle/libs-releases")
    maven(uri("$projectDir/lib"))
    maven(url = "https://jitpack.io")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("compiler"))
    implementation(kotlin("scripting-compiler"))
    implementation(kotlin("scripting-jvm-host-unshaded"))
    implementation(kotlin("sam-with-receiver-compiler-plugin"))
    implementation(kotlin("reflect"))

    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:$lsp4jVersion")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:$lsp4jVersion")
    implementation("org.jetbrains:fernflower:1.0")
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("com.google.guava:guava:33.3.1-jre")
    implementation("com.h2database:h2:2.2.220")
    implementation("com.beust:jcommander:1.78")
    implementation("org.xerial:sqlite-jdbc:3.41.2.2")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.1")
    testImplementation("org.hamcrest:hamcrest-all:1.3")
    testImplementation("org.openjdk.jmh:jmh-core:1.20")

    // See https://github.com/JetBrains/kotlin/blob/65b0a5f90328f4b9addd3a10c6f24f3037482276/libraries/examples/scripting/jvm-embeddable-host/build.gradle.kts#L8
    compileOnly(kotlin("scripting-jvm-host"))
    testCompileOnly(kotlin("scripting-jvm-host"))

    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.20")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set(mainClassName)
}

tasks.withType<Jar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = mainClassName
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
    }
}

tasks.withType<Tar> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Zip> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
