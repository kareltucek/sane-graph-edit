import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// A client of an editor session, without any GUI toolkit: keeps
// replicas of the documents it has open and sends its changes.
// Multiplatform: the same client for the Swing application (JVM)
// and for renderers written for JavaScript.
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
        // client.Fs and client.AppState are expect objects
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":model"))
        }
        commonTest.dependencies {
            implementation(project(":editor-core"))
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
