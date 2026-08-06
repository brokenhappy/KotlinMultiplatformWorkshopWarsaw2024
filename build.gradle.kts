plugins {
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlinx.rpc) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.jib) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.hot.reload) apply false
    alias(libs.plugins.compose) apply false
}
