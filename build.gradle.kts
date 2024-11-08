plugins {
    kotlin("multiplatform") version "2.0.10" apply false
    kotlin("plugin.serialization") version "2.0.10" apply false
    id("com.android.library") version "8.5.2" apply false
    id("com.android.application") version "8.5.2" apply false
    id("com.google.devtools.ksp") version "2.0.10-1.0.24" apply false
    id("org.jetbrains.kotlinx.rpc.plugin") version "0.2.4" apply false
    kotlin("jvm") version "2.0.10" apply false
    id("io.ktor.plugin") version "2.3.12" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.10" apply false
    id("org.jetbrains.compose") version "1.6.11" apply false
}
