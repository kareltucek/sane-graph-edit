import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// The authoritative side of a session: documents, ordering of
// changes, undo. Pure logic — no threads, sockets or file system;
// a host drives it and supplies those.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    api(project(":model"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
