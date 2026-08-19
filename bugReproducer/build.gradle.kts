plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    implementation(project(":client"))
    implementation(project(":server"))
    implementation(project(":common"))
    implementation(project(":serverAndAdminCommon"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.ui)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(compose.desktop.uiTestJUnit4)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

compose.desktop {
    application {
        mainClass = "bugreproducer.MainKt"
    }
}
