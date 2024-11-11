import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

repositories {
    google()
}

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jetbrains.kotlinx.rpc.plugin")
}

group = "com.woutwerkman"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

android {
    namespace = "com.woutwerkman.shared"
    compileSdk = 34
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        minSdk = 24
    }
}

kotlin {
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation("io.ktor:ktor-client-core:3.0.1")
            implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-client:0.4.0")
            implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-ktor-client:0.4.0")
//            implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-server")
//            implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-ktor-server")
            implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-serialization-json:0.4.0")
        }
    }
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-receivers")
    }
}