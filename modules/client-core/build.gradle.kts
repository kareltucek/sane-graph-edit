import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest

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
        nodejs {
            testTask {
                // the WebSocket tests start an editor in a JVM of its own, see below
                useMocha { timeout = "30s" }
            }
        }
        // what a renderer written in TypeScript links against (`client.Bridge`), with type declarations
        binaries.library()
        generateTypeScriptDefinitions()
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

// The JavaScript client is tested against a real editor: `host.TestEditor`
// from the tests of :editor-host, which the test starts with what it is told here.
tasks.withType<KotlinJsTest> {
    dependsOn(":editor-host:jvmTestClasses")
    val java = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    doFirst {
        val editorTests = project(":editor-host").extensions.getByType<KotlinMultiplatformExtension>().jvm().compilations["test"]
        environment("SGE_TEST_JAVA", java.get().executablePath.asFile.path)
        environment("SGE_TEST_EDITOR_CLASSPATH", (editorTests.output.allOutputs + editorTests.runtimeDependencyFiles!!).asPath)
    }
}
