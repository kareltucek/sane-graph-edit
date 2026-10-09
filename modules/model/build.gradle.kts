import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// The graph model: data, undo history, layout, DOT, export.
// No AWT / Swing in here — clients and the editor both use it.
// Multiplatform: the JVM for the Swing application and the editor
// host, JavaScript (Node.js) for clients written for it.
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
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
        // utils.Platform is an expect object
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    sourceSets {
        commonMain.dependencies {
            // the wire form of the client ↔ editor protocol
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
