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
    mavenLocal()
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
    // The client includes the Java 25 call-tree visualizer UI.
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

// Compose's hotRun task otherwise defaults to the IDE/Gradle JVM (currently Java 21),
// which cannot load the Java 25 classes supplied by call-tree-ui.
val java25Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
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
