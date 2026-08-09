import org.gradle.kotlin.dsl.implementation

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinx.rpc)
}

repositories {
    mavenLocal()
    google()
    mavenCentral()
}

group = "com.woutwerkman"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(project(":workshopApi"))
    implementation(libs.calltreevisualizer.flow.context.agent.api)
    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.rpc.krpc.client)
    implementation(libs.kotlinx.rpc.krpc.ktor.client)
    implementation(libs.kotlinx.rpc.krpc.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.collections.immutable)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test { useJUnitPlatform() }

kotlin {
    jvmToolchain(23)
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}
