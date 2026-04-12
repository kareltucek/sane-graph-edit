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

// ---------- AppImage: single self-contained .AppImage file ----------
//
// Usage:
//   ./gradlew appimage
//
// Requires appimagetool on $PATH (or at ~/.local/bin/appimagetool).
// Install:
//   wget -O ~/.local/bin/appimagetool \
//     https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage
//   chmod +x ~/.local/bin/appimagetool
//
// Output: build/dist/sane-graph-edit-<version>-x86_64.AppImage
tasks.register<Exec>("appimage") {
    group = "distribution"
    description = "Builds a self-contained .AppImage via appimagetool."
    dependsOn("jpackage")

    val jpackageOut = layout.buildDirectory.dir("dist/sane-graph-edit").get().asFile
    val appDir = layout.buildDirectory.dir("appimage-staging").get().asFile
    val outputDir = layout.buildDirectory.dir("dist").get().asFile
    val ver = project.version.toString().removeSuffix("-SNAPSHOT")
    val packagingDir = project.file("packaging")

    doFirst {
        // Build the AppDir structure expected by appimagetool:
        //   AppDir/
        //     AppRun              (entry-point script)
        //     sane-graph-edit.desktop
        //     sane-graph-edit.png (icon)
        //     bin/, lib/          (from jpackage output)
        if (appDir.exists()) appDir.deleteRecursively()
        appDir.mkdirs()

        // Copy the full jpackage tree (launcher + JRE + jars).
        // Kotlin's copyRecursively does NOT preserve POSIX permissions,
        // so we restore +x on executables and shared libs below —
        // without this the squashfs mount is read-only and the
        // native launcher fails with "Permission denied".
        jpackageOut.copyRecursively(appDir, overwrite = true)

        // Restore execute bits that copyRecursively dropped.
        appDir.resolve("bin/sane-graph-edit").setExecutable(true)
        appDir.walk()
            .filter { it.extension == "so" || it.name in listOf("jspawnhelper", "jexec") }
            .forEach { it.setExecutable(true) }

        // Layer the AppImage metadata on top
        packagingDir.resolve("AppRun").copyTo(appDir.resolve("AppRun"), overwrite = true)
        appDir.resolve("AppRun").setExecutable(true)
        packagingDir.resolve("sane-graph-edit.desktop")
            .copyTo(appDir.resolve("sane-graph-edit.desktop"), overwrite = true)
        // Use the icon jpackage generated, or fall back to a placeholder
        val icon = jpackageOut.resolve("lib/sane-graph-edit.png")
        if (icon.exists()) {
            icon.copyTo(appDir.resolve("sane-graph-edit.png"), overwrite = true)
        }
    }

    // Find appimagetool — check PATH and the common ~/.local/bin location
    val home = System.getProperty("user.home")
    val appimagetool = listOf("/usr/bin/appimagetool", "/usr/local/bin/appimagetool", "$home/.local/bin/appimagetool")
        .firstOrNull { File(it).exists() }
        ?: "appimagetool"  // fall back to PATH lookup

    executable = appimagetool
    args(appDir.absolutePath, outputDir.resolve("sane-graph-edit-$ver-x86_64.AppImage").absolutePath)

    // appimagetool needs ARCH set for the filename convention
    environment("ARCH", "x86_64")
}
