plugins {
    kotlin("jvm") version "2.0.10"
    kotlin("plugin.serialization") version "2.0.10"
    id("io.ktor.plugin") version "2.3.12"
    id("com.google.devtools.ksp") version "2.0.10-1.0.24"
    id("org.jetbrains.kotlinx.rpc.plugin") version "0.2.4" // Don't ask my why I need all of this, I'm just trying to make my build pass okay? :((
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
    testImplementation(kotlin("test"))
    implementation(project(":common"))
    implementation(project(":server"))
    implementation(project(":client"))
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-receivers")
    }
}