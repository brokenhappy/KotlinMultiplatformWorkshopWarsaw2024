plugins {
    kotlin("plugin.serialization") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
    id("org.jetbrains.kotlinx.rpc.plugin") version "0.10.3" apply false
    kotlin("jvm") version "2.4.10" apply false
    id("com.google.cloud.tools.jib") version "3.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.compose.hot-reload") version "1.0.0-rc02" apply false
    id("org.jetbrains.compose") version "1.8.2" apply false
}
