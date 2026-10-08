import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// Runs an editor session on the JVM: gives the editor core its
// files and its connections (in-process, later a socket).
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
    api(project(":editor-core"))
    api(project(":client"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
