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
    implementation("io.ktor:ktor-client-core:3.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-client:0.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-ktor-client:0.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-serialization-json:0.9.1")
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