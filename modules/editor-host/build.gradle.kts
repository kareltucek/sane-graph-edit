import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// Runs an editor session: gives the editor core its files and its
// connections. Multiplatform: an editor in the process that shows
// it is the same on the JVM and in JavaScript; the sockets other
// processes attach through are the JVM's so far.
plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(21)
    jvm()
    js(IR) {
        nodejs()
        // what the Electron client links against: this module's `host.*` and, with it, the client's `client.*`, with type declarations
        binaries.library()
        generateTypeScriptDefinitions()
    }
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":editor-core"))
            api(project(":client-core"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
