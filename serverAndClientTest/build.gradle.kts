plugins {
    kotlin("jvm")
    application
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlinx.rpc.plugin")
}

group = "com.woutwerkman"
version = "unspecified"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(project(":common"))
    implementation(project(":server"))
    implementation(project(":serverAndAdminCommon"))
    implementation(project(":client"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-netty-jvm:3.3.0")
    testImplementation("io.ktor:ktor-client-cio-jvm:3.3.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-server:0.9.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-client:0.9.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-ktor-server:0.9.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-ktor-client:0.9.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-serialization-json:0.9.1")
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