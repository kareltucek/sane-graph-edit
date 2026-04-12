import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.0.21"
    application
}

group = "sane"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Explicit so bumps to the Kotlin plugin (which may ship a
        // newer default) don't silently change language semantics.
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
    }
}

application {
    // ui.MainKt is the entry point (ui package, top-level `fun main`).
    mainClass.set("ui.MainKt")
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Produce a runnable fat jar for distribution.
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Assembles a runnable jar with all runtime dependencies included."
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// ---------- jpackage: native app-image or installer ----------
//
// Usage:
//   ./gradlew jpackage              -- app-image (directory with launcher + bundled JRE)
//   ./gradlew jpackage -Ptype=deb   -- Debian .deb package
//   ./gradlew jpackage -Ptype=rpm   -- RPM package
//
// Output lands in build/dist/.
// Requires JDK 21+ (ships jpackage) and, for deb/rpm, the
// corresponding packaging tools (dpkg-deb / rpmbuild).
tasks.register<Exec>("jpackage") {
    group = "distribution"
    description = "Builds a native app-image (or installer) via jpackage."
    dependsOn("fatJar")

    val fatJarFile = tasks.named<Jar>("fatJar").get().archiveFile.get().asFile
    val outputDir = layout.buildDirectory.dir("dist").get().asFile
    val pkgType = project.findProperty("type")?.toString() ?: "app-image"

    // Resolve the jpackage binary from the same toolchain Gradle
    // uses for compilation, so we don't accidentally pick up a
    // system JDK that's too old.
    val javaHome = javaToolchains
        .launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        .get()
        .metadata
        .installationPath
        .asFile
    val jpackageBin = javaHome.resolve("bin/jpackage")

    doFirst {
        // jpackage refuses to overwrite an existing output directory,
        // so wipe it before each run.
        val existing = outputDir.resolve("sane-graph-edit")
        if (existing.exists()) existing.deleteRecursively()
        outputDir.mkdirs()
    }

    executable = jpackageBin.absolutePath
    args(
        "--input", fatJarFile.parentFile.absolutePath,
        "--main-jar", fatJarFile.name,
        "--main-class", application.mainClass.get(),
        "--name", "sane-graph-edit",
        "--app-version", project.version.toString().removeSuffix("-SNAPSHOT"),
        "--type", pkgType,
        "--dest", outputDir.absolutePath,
        // Swing needs a few JVM flags on newer JDKs to avoid
        // warnings; pass them through to the native launcher.
        "--java-options", "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
    )
}
