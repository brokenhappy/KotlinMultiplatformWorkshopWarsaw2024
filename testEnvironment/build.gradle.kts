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
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

// Compose launch variants and Hot Reload use the same DCEVM-capable runtime.
val java25Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
    vendor.set(JvmVendorSpec.JETBRAINS)
}
System.setProperty(
    "compose.reload.jbr.binary",
    java25Launcher.get().executablePath.asFile.absolutePath,
)

compose.desktop {
    application {
        mainClass = "TestEnvironmentMainKt"
        javaHome = java25Launcher.get().metadata.installationPath.asFile.absolutePath
    }
}
