import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// The authoritative side of a session: documents, ordering of
// changes, undo. Pure logic — no threads, sockets or file system;
// a host drives it and supplies those. Multiplatform, so that a
// host can be written for Node.js as well.
plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(21)
    jvm()
    js(IR) {
        nodejs()
    }
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":model"))
        }
    }
}
