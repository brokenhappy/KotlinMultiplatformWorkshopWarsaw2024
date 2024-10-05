plugins {
    kotlin("jvm") version "2.0.10"
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

dependencies {
    implementation(project(":common"))
    implementation("io.ktor:ktor-server-netty-jvm:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-server")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-ktor-server")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-serialization-json")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation(compose.desktop.currentOs)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}