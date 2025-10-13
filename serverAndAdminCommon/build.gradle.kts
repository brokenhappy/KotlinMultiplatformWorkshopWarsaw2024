plugins {
    kotlin("jvm")
    application
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlinx.rpc.plugin")
}

group = "com.woutwerkman"
version = "unspecified"

application {
    mainClass.set("kmpworkshop.server.ServerKt")
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(project(":common"))
    implementation("io.ktor:ktor-client-core:3.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-client:0.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-serialization-json:0.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}