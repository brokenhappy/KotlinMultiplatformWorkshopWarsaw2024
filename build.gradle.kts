plugins {
    kotlin("plugin.serialization") version "2.2.0" apply false
    id("com.google.devtools.ksp") version "2.2.0-2.0.2" apply false
    id("org.jetbrains.kotlinx.rpc.plugin") version "0.9.1" apply false
    kotlin("jvm") version "2.2.0" apply false
    id("com.google.cloud.tools.jib") version "3.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
    id("org.jetbrains.compose") version "1.8.2" apply false
}
