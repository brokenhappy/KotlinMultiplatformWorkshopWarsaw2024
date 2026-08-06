import org.gradle.kotlin.dsl.implementation


plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlinx.rpc.plugin")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("org.jetbrains.compose.hot-reload")
}

group = "com.woutwerkman"
version = "unspecified"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("io.ktor:ktor-client-cio-jvm:3.3.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.3.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.0")
    implementation("io.ktor:ktor-client-core:3.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-client:0.10.3")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-ktor-client:0.10.3")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-serialization-json:0.10.3")
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.ui)
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.hot-reload:hot-reload-runtime-api:1.0.0-rc02")
    implementation(project(":common"))
    implementation(project(":workshopApi"))
    implementation(project(":workshopSolutions"))
    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

compose.desktop {
    application {
        mainClass = "kmpworkshop.client.MainKt"
    }
}
