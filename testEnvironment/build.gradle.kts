plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlinx.rpc.plugin") // Don't ask my why I need all of this, I'm just trying to make my build pass okay? :((
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

group = "com.woutwerkman"
version = "unspecified"

repositories {
    mavenCentral()
    google()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation(project(":common"))
    implementation(project(":server"))
    implementation(project(":client"))
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-server")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
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