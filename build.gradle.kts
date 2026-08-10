import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test

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
}
