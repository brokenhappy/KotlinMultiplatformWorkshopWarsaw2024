import org.gradle.kotlin.dsl.implementation


plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinx.rpc)
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
    implementation(libs.ktor.client.cio.jvm)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.rpc.krpc.client)
    implementation(libs.kotlinx.rpc.krpc.ktor.client)
    implementation(libs.kotlinx.rpc.krpc.serialization.json)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.ui)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.hot.reload.runtime.api)
    implementation(libs.calltreevisualizer.tracked.flow)
    implementation(libs.calltreevisualizer.call.tree.ui)
    implementation(project(":common"))
    implementation(project(":workshopApi"))
    implementation(project(":workshopSolutions"))
    testImplementation(libs.kotlin.test)
    testImplementation(compose.desktop.uiTestJUnit4)
}

tasks.test {
    // Compose generates implementation classes whose names contain `Test`; they are not JUnit tests.
    exclude("**/ComposableSingletons*")
}

kotlin {
    // The published call-tree UI is built for Java 25.
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

compose.desktop {
    application {
        mainClass = "kmpworkshop.client.MainKt"
        // Run with the same JVM that can load the Java 25 call-tree UI.
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }.get().metadata.installationPath.asFile.absolutePath
    }
}

// Compose Hot Reload defaults to a Java 21 JetBrains Runtime; the call-tree UI is
// Java 25 bytecode, so use the project toolchain for all Compose launch variants.
val java25Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}
System.setProperty(
    "compose.reload.jbr.binary",
    java25Launcher.get().executablePath.asFile.absolutePath,
)
