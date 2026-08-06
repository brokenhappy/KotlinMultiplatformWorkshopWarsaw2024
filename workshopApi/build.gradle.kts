plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.woutwerkman"
version = "1.0-SNAPSHOT"

repositories { mavenCentral() }

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
}

kotlin {
    jvmToolchain(17)
    compilerOptions.freeCompilerArgs.add("-Xcontext-parameters")
}
