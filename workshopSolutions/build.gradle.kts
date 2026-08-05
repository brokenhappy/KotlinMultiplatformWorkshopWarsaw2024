plugins {
    kotlin("jvm")
}

group = "com.woutwerkman"
version = "1.0-SNAPSHOT"

repositories { mavenCentral() }

dependencies {
    implementation(project(":workshopApi"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

kotlin {
    jvmToolchain(17)
    compilerOptions.freeCompilerArgs.add("-Xcontext-parameters")
}
