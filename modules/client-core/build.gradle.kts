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

// The examples and the documentation they lead to, as Kotlin source: the application carries them and
// writes them out when it starts (`client.HelpFiles`), so that the built-in help can lead to them.
val helpFiles = tasks.register("generateHelpFiles") {
    val root = rootProject.projectDir
    val sources = rootProject.fileTree("examples") { include("*.dot", "*.txt") } + rootProject.files("README.md", "docs/file-format.md", "docs/vr-controls.dot")
    val out = layout.buildDirectory.dir("generated/helpFiles")
    inputs.files(sources)
    outputs.dir(out)
    doLast {
        val literal = { text: String -> "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("\n", "\\n") + "\"" }
        val entries = sources.files.sortedBy { it.path }.joinToString("\n") { "        ${literal(it.relativeTo(root).invariantSeparatorsPath)} to ${literal(it.readText())}," }
        out.get().file("client/HelpFilesContent.kt").asFile.also { it.parentFile.mkdirs() }
            .writeText("package client\n\n/** Generated from the repository's files; see build.gradle.kts. */\ninternal object HelpFilesContent {\n    val files: Map<String, String> = mapOf(\n$entries\n    )\n}\n")
    }
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
        commonMain {
            kotlin.srcDir(helpFiles)
            dependencies {
                api(project(":model"))
            }
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
