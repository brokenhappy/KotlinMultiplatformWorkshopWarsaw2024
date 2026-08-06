plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jib)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinx.rpc)
}

group = "com.woutwerkman"
version = "unspecified"

jib {
    from {
        image = "amazoncorretto:17"
    }
}

application {
    mainClass.set("kmpworkshop.server.ServerKt")
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(project(":workshopApi"))
    implementation(project(":common"))
    implementation(project(":serverAndAdminCommon"))
    implementation(libs.ktor.server.netty.jvm)
    implementation(libs.kotlinx.rpc.krpc.server)
    implementation(libs.kotlinx.rpc.krpc.ktor.server)
    implementation(libs.kotlinx.rpc.krpc.serialization.json)
    implementation(libs.logback.classic.server)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.collections.immutable)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test)
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
