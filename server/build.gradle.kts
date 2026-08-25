import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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
        image = "amazoncorretto:25"
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
    testImplementation(libs.ktor.client.cio.jvm)
    testImplementation(libs.kotlinx.rpc.krpc.client)
    testImplementation(libs.kotlinx.rpc.krpc.ktor.client)
    testImplementation(libs.kotlinx.rpc.krpc.serialization.json)
}

tasks.test {
    useJUnitPlatform()
}

val serverProvenanceOutput = layout.buildDirectory.file("generated/resources/server-provenance.json")
val generateServerProvenance by tasks.registering {
    outputs.file(serverProvenanceOutput)
    outputs.upToDateWhen { false }
    doLast {
        data class GitResult(val stdout: String, val stderr: String, val exitCode: Int)

        fun git(vararg arguments: String): GitResult? = runCatching {
            val stdoutFile = File.createTempFile("git-", ".stdout", temporaryDir)
            val stderrFile = File.createTempFile("git-", ".stderr", temporaryDir)
            try {
                val process = ProcessBuilder(listOf("git") + arguments)
                    .directory(rootDir)
                    .redirectOutput(stdoutFile)
                    .redirectError(stderrFile)
                    .start()
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor()
                    return@runCatching GitResult("", "git command timed out", -1)
                }
                GitResult(stdoutFile.readText(), stderrFile.readText(), process.exitValue())
            } finally {
                stdoutFile.delete()
                stderrFile.delete()
            }
        }.getOrNull()

        fun jsonString(value: String?): String? = value?.let { input ->
            buildString {
                append('"')
                input.forEach { character ->
                    when (character) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\b' -> append("\\b")
                        '\t' -> append("\\t")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        else -> if (character.code < 0x20) {
                            append("\\u")
                            append(character.code.toString(16).padStart(4, '0'))
                        } else {
                            append(character)
                        }
                    }
                }
                append('"')
            }
        }

        fun jsonOrNull(value: String?): String = jsonString(value) ?: "null"

        fun sha256(value: String?): String? = value?.let {
            MessageDigest.getInstance("SHA-256")
                .digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        }

        fun failureFor(label: String, result: GitResult?): String {
            val detail = result?.stderr?.take(500)?.replace('\n', ' ').orEmpty()
            return "$label unavailable${if (detail.isBlank()) "" else ": $detail"}"
        }

        val failures = mutableListOf<String>()
        val commitResult = git("rev-parse", "HEAD")
        val commit = commitResult
            ?.takeIf { it.exitCode == 0 }
            ?.stdout
            ?.trim()
            ?: System.getenv("BUILD_COMMIT")
            ?: run { failures += failureFor("git commit", commitResult); null }

        val changesResult = git(
            "diff",
            "--no-ext-diff",
            "--binary",
            "HEAD",
            "--",
            "common",
            "server",
            "serverAndAdminCommon",
        )
        val changesSource = changesResult?.takeIf { it.exitCode == 0 }?.stdout
            ?: System.getenv("BUILD_CHANGES")
            ?: run { failures += failureFor("git changes", changesResult); null }
        val changesSha256 = sha256(changesSource)
        val changes = changesSource
        val changesTruncated = false

        val untrackedResult = git(
            "ls-files",
            "-z",
            "--others",
            "--exclude-standard",
            "--",
            "common",
            "server",
            "serverAndAdminCommon",
        )
        val nullDevice = if (System.getProperty("os.name").contains("windows", ignoreCase = true)) "NUL" else "/dev/null"
        val untrackedChangesRaw: String? = if (untrackedResult?.exitCode == 0) {
            buildString {
                untrackedResult.stdout
                    .split('\u0000')
                    .filter { it.isNotEmpty() }
                    .forEach { path ->
                        val safeRelativePath = if (path.startsWith("./")) path else "./$path"
                        val result = git(
                            "diff",
                            "--no-ext-diff",
                            "--binary",
                            "--no-index",
                            nullDevice,
                            safeRelativePath,
                        )
                        if (result == null || result.exitCode !in 0..1) {
                            failures += failureFor("untracked file $path", result)
                        } else {
                            append(result.stdout)
                        }
                    }
            }
        } else {
            failures += failureFor("git untracked files", untrackedResult)
            null
        }
        val untrackedChangesSource = if (untrackedResult?.exitCode == 0) {
            untrackedChangesRaw
        } else {
            System.getenv("BUILD_UNTRACKED_CHANGES")
        }
        val untrackedChangesSha256 = sha256(untrackedChangesSource)
        val untrackedChanges = untrackedChangesSource
        val untrackedChangesTruncated = false

        val escapedFailures = failures.joinToString(",") { jsonOrNull(it) }
        val output = serverProvenanceOutput.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            "{\"commit\":${jsonOrNull(commit)}," +
                "\"changes\":${jsonOrNull(changes)}," +
                "\"changesTruncated\":$changesTruncated," +
                "\"changesSha256\":${jsonOrNull(changesSha256)}," +
                "\"untrackedChanges\":${jsonOrNull(untrackedChanges)}," +
                "\"untrackedChangesTruncated\":$untrackedChangesTruncated," +
                "\"untrackedChangesSha256\":${jsonOrNull(untrackedChangesSha256)}," +
                "\"failures\":[$escapedFailures]}"
        )
    }
}

tasks.processResources {
    dependsOn(generateServerProvenance)
    from(layout.buildDirectory.dir("generated/resources"))
}
