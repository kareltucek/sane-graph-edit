import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// A client of an editor session, without any GUI toolkit: keeps
// replicas of the documents it has open and sends its changes.
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
    testImplementation(project(":editor-core"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
