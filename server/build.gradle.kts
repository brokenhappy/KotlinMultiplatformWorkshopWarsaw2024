plugins {
    kotlin("jvm")
    application
    kotlin("plugin.serialization")
    id("com.google.cloud.tools.jib")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlinx.rpc.plugin")
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
    implementation(project(":common"))
    implementation(project(":serverAndAdminCommon"))
    implementation("io.ktor:ktor-server-netty-jvm:3.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-server:0.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-ktor-server:0.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-serialization-json:0.9.1")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0")
    testImplementation(kotlin("test"))
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
