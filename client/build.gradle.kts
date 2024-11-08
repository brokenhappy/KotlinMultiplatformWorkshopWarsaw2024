plugins {
    kotlin("multiplatform") version "2.0.10"
    kotlin("plugin.serialization") version "2.0.10"
    id("io.ktor.plugin") version "2.3.12"
    id("com.google.devtools.ksp") version "2.0.10-1.0.24"
    id("org.jetbrains.kotlinx.rpc.plugin") version "0.2.4"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.10"
    id("org.jetbrains.compose") version "1.6.11"
}

group = "com.woutwerkman"
version = "unspecified"

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvm {
        withJava()
    }
    sourceSets {
        commonMain.dependencies {
            implementation("io.ktor:ktor-client-core")
            implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-client")
            implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-ktor-client")
            implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-serialization-json")
            implementation(compose.desktop.currentOs)
            implementation(project(":common"))
        }
        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp")
        }
    }
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-receivers")
    }
}