// The subprojects are in modules/, see settings.gradle.kts. The
// application itself is :client-swing.
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("multiplatform") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
}

allprojects {
    group = "sane"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
