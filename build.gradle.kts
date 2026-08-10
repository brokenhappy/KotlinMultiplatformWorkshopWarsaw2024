import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

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

val flowContextAgentDependency = libs.calltreevisualizer.flow.context.agent

subprojects {
    val flowContextAgent by configurations.creating {
        isTransitive = false
    }

    dependencies {
        add(flowContextAgent.name, flowContextAgentDependency)
    }

    fun agentArgument(): String = "-javaagent:${flowContextAgent.singleFile.absolutePath}"

    tasks.withType<Test>().configureEach {
        jvmArgs(agentArgument())
    }
    tasks.withType<JavaExec>().configureEach {
        jvmArgs(agentArgument())
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain {
                languageVersion.set(JavaLanguageVersion.of(25))
                vendor.set(JvmVendorSpec.JETBRAINS)
            }
        }

        tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_23)
                freeCompilerArgs.add("-Xjdk-release=23")
            }
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(23)
    }
}
