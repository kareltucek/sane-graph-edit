import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// The graph model: data, undo history, layout, DOT, export.
// No AWT / Swing in here — clients and the editor both use it.
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
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
