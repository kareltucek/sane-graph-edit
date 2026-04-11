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
