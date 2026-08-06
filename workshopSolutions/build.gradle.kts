plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.calltreevisualizer)
}

group = "com.woutwerkman"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(project(":workshopApi"))
    implementation(libs.kotlinx.coroutines.core)
}

kotlin {
    jvmToolchain(17)
    compilerOptions.freeCompilerArgs.add("-Xcontext-parameters")
}
