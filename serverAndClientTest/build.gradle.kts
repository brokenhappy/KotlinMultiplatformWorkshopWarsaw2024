plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinx.rpc)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
}

group = "com.woutwerkman"
version = "unspecified"

repositories {
    mavenLocal()
    mavenCentral()
    google()
}

dependencies {
    implementation(project(":workshopApi"))
    implementation(project(":workshopSolutions"))
    implementation(project(":common"))
    implementation(project(":server"))
    implementation(project(":serverAndAdminCommon"))
    implementation(project(":client"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.netty.jvm)
    testImplementation(libs.ktor.client.cio.jvm)
    testImplementation(libs.kotlinx.rpc.krpc.server)
    testImplementation(libs.kotlinx.rpc.krpc.client)
    testImplementation(libs.kotlinx.rpc.krpc.ktor.server)
    testImplementation(libs.kotlinx.rpc.krpc.ktor.client)
    testImplementation(libs.kotlinx.rpc.krpc.serialization.json)
    testImplementation(libs.calltreevisualizer.stack.tracking.core.api)
    testImplementation(libs.calltreevisualizer.flow.context.agent.api)
    testImplementation(project(":testEnvironment"))
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(compose.desktop.currentOs)
    testImplementation(compose.material3)
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    // Keep test compilation and execution on the project-wide JVM version.
    jvmToolchain(23)
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}
