package kmpworkshop.client

import kmpworkshop.common.ClientBugDiagnostics
import kmpworkshop.common.MaxBugDiagnosticMetadataLength
import kmpworkshop.common.MaxBugDiagnosticValueLength
import kmpworkshop.common.coroutinesToLoom
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

private val diagnosticGitPaths = arrayOf(
    "--",
    ":(exclude,glob)**/AdminPassword.kt",
    ":(exclude,glob)**/Secrets.kt",
    ":(exclude,glob)**/Secrets.ini",
    ":(exclude,glob)**/.env*",
    ":(exclude,glob)**/.workshop-api-key*",
)

internal suspend fun collectClientBugDiagnostics(settings: ClientSettings): ClientBugDiagnostics {
    val values = linkedMapOf<String, String>()
    val failures = mutableListOf<String>()

    fun collect(name: String, value: () -> String) {
        runCatching { value() }
            .onSuccess { values[name] = it.take(MaxBugDiagnosticMetadataLength) }
            .onFailure { failures += "$name: ${it.message ?: it::class.simpleName}" }
    }

    collect("client.os") { System.getProperty("os.name") }
    collect("client.os.version") { System.getProperty("os.version") }
    collect("client.os.architecture") { System.getProperty("os.arch") }
    collect("client.jvm") { System.getProperty("java.runtime.version") }
    collect("client.jvm.vendor") { System.getProperty("java.vendor") }
    collect("client.java.home") { System.getProperty("java.home") }
    collect("client.settings") { "zoom=${settings.zoom}" }
    collect("client.workingDirectory") { File(".").absoluteFile.normalize().path }

    collectGitDiagnostics(values, failures)
    return ClientBugDiagnostics(values, failures.map { it.take(MaxBugDiagnosticMetadataLength) })
}

private suspend fun collectGitDiagnostics(values: MutableMap<String, String>, failures: MutableList<String>) {
    val repository = runGit("rev-parse", "--show-toplevel")
        .map { it.trim() }
        .getOrElse {
            failures += "client.git.repository: ${it.message ?: it::class.simpleName}"
            return
        }
    values["client.git.repository"] = repository.take(MaxBugDiagnosticMetadataLength)

    suspend fun gitValue(name: String, preserveWhitespace: Boolean, vararg command: String) {
        runGit(*command, directory = File(repository))
            .onSuccess { output ->
                val value = if (preserveWhitespace) output else output.trim()
                values[name] = value.take(MaxBugDiagnosticValueLength)
                if (output.length > MaxBugDiagnosticValueLength) {
                    values["${name}Truncated"] = "true"
                }
            }
            .onFailure { failures += "$name: ${it.message ?: it::class.simpleName}" }
    }

    gitValue("client.git.checkedOutCommit", false, "rev-parse", "HEAD")
    val originHead = runGit("rev-parse", "--verify", "origin/HEAD", directory = File(repository))
        .recoverCatching {
            runGit(
                "for-each-ref",
                "--sort=-committerdate",
                "--format=%(refname)",
                "refs/remotes/origin",
                directory = File(repository),
            ).getOrThrow().lineSequence().map { it.trim() }.first { it != "refs/remotes/origin/HEAD" }
        }
        .map { it.trim() }
    originHead
        .onSuccess { origin ->
            val nearestOriginCommit = runGit(
                "merge-base", "HEAD", origin, directory = File(repository),
            ).map { it.trim() }.getOrElse {
                failures += "client.git.nearestOriginCommit: ${it.message ?: it::class.simpleName}"
                return@onSuccess
            }
            values["client.git.nearestOriginCommit"] = nearestOriginCommit.take(MaxBugDiagnosticMetadataLength)
            gitValue(
                "client.git.changesSinceOrigin",
                true,
                "diff", "--no-ext-diff", "--binary", "${values["client.git.nearestOriginCommit"]}..HEAD",
                *diagnosticGitPaths,
            )
        }
        .onFailure { failures += "client.git.origin: ${it.message ?: it::class.simpleName}" }

    gitValue("client.git.localChanges", true, "diff", "--no-ext-diff", "--binary", "HEAD", *diagnosticGitPaths)
    gitValue("client.git.untrackedFiles", false, "ls-files", "--others", "--exclude-standard", *diagnosticGitPaths)
}

private suspend fun runGit(
    vararg command: String,
    directory: File = File("."),
): Result<String> = try {
    coroutineScope {
        val process = coroutinesToLoom {
            ProcessBuilder(listOf("git") + command)
                .directory(directory)
                .redirectErrorStream(false)
                .start()
        }
        process.outputStream.close()
        val output = async { coroutinesToLoom { readLimited(process.inputStream, MaxBugDiagnosticValueLength + 1) } }
        val error = async { coroutinesToLoom { readLimited(process.errorStream, 4_000) } }
        try {
            if (!coroutinesToLoom { process.waitFor(3, TimeUnit.SECONDS) }) {
                error("git command timed out")
            }
            val outputText = output.await()
            val errorText = error.await()
            if (process.exitValue() != 0) {
                error(errorText.trim().take(500).ifBlank { "git failed" })
            }
            outputText
        } finally {
            if (process.isAlive) terminateGitProcess(process)
        }
    }.let { Result.success(it) }
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Throwable) {
    Result.failure(failure)
}

private suspend fun terminateGitProcess(process: Process) {
    withContext(NonCancellable) {
        coroutinesToLoom {
            runCatching { process.toHandle().descendants().forEach { it.destroyForcibly() } }
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(1, TimeUnit.SECONDS) }
        }
    }
}

private fun readLimited(input: InputStream, limit: Int): String {
    val buffer = CharArray(8_192)
    return input.reader(Charsets.UTF_8).use { reader ->
        buildString {
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                appendRange(buffer, 0, minOf(read, limit - length))
            }
        }
    }
}
