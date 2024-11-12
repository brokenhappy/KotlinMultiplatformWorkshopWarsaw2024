import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlinx.rpc.plugin")
    application
}

application {
    mainClass = "RegistrationScaffoldingKt"
}

group = "com.woutwerkman"
version = "unspecified"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-client")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-ktor-client")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-serialization-json")
    testImplementation(kotlin("test"))
    implementation(project(":client"))
    implementation(project(":common"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}