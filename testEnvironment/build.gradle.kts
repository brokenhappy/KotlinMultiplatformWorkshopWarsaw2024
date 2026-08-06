plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
//    id("io.ktor.plugin")
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinx.rpc) // Don't ask my why I need all of this, I'm just trying to make my build pass okay? :((
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

group = "com.woutwerkman"
version = "unspecified"

repositories {
    mavenCentral()
    google()
}

dependencies {
    testImplementation(libs.kotlin.test)
    implementation(project(":common"))
    implementation(project(":server"))
    implementation(project(":adminClient"))
    implementation(project(":serverAndAdminCommon"))
    implementation(project(":client"))
    implementation(libs.kotlinx.rpc.krpc.server)
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
