import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget


plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlinx.rpc.plugin")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

android {
    namespace = "com.woutwerkman"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.woutwerkman"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

group = "com.woutwerkman"
version = "unspecified"

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvm()
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation("io.ktor:ktor-client-cio:2.3.12")
            implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
            implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
            implementation("io.ktor:ktor-client-core:2.3.12")
            implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-client")
            implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-ktor-client")
            implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-serialization-json")
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material)
            implementation(compose.ui)
            implementation(project(":common"))
        }
        jvmMain.dependencies {
            implementation("org.slf4j:slf4j-simple:1.7.32")
            implementation(compose.desktop.currentOs)
        }
        androidMain.dependencies {
            implementation(compose.preview)
            implementation("androidx.activity:activity-compose:1.9.3")
        }
        iosMain.dependencies {
        }
    }
    jvmToolchain(17)
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}
dependencies {
    implementation("io.ktor:ktor-client-cio-jvm:2.3.12")
}

compose.desktop {
    application {
        mainClass = "com.woutwerkman.MainKt"
    }
}