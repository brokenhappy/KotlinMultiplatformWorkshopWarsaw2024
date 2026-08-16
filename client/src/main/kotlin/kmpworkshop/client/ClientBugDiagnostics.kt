package kmpworkshop.client

import kmpworkshop.common.ClientBugDiagnostics
import kmpworkshop.common.MaxBugDiagnosticValueLength
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit

internal fun collectClientBugDiagnostics(settings: ClientSettings): ClientBugDiagnostics {
    val values = linkedMapOf<String, String>()
    val failures = mutableListOf<String>()

    fun collect(name: String, value: () -> String) {
        runCatching { value() }
            .onSuccess { values[name] = it.take(MaxBugDiagnosticValueLength) }
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
    return ClientBugDiagnostics(values, failures)
}

private fun collectGitDiagnostics(values: MutableMap<String, String>, failures: MutableList<String>) {
    val repository = runGit("rev-parse", "--show-toplevel")
        .getOrElse {
            failures += "client.git.repository: ${it.message ?: it::class.simpleName}"
            return
        }
    values["client.git.repository"] = repository

    fun gitValue(name: String, vararg command: String) {
        runGit(*command, directory = File(repository))
            .onSuccess { values[name] = it }
            .onFailure { failures += "$name: ${it.message ?: it::class.simpleName}" }
    }

    gitValue("client.git.checkedOutCommit", "rev-parse", "HEAD")
    val originHead = runGit("rev-parse", "--verify", "origin/HEAD", directory = File(repository))
        .recoverCatching {
            runGit(
                "for-each-ref",
                "--sort=-committerdate",
                "--format=%(refname)",
                "refs/remotes/origin",
                directory = File(repository),
            ).getOrThrow().lineSequence().first { it != "refs/remotes/origin/HEAD" }
        }
    originHead
        .onSuccess { origin ->
            values["client.git.nearestOriginCommit"] = runGit(
                "merge-base", "HEAD", origin, directory = File(repository),
            ).getOrElse {
                failures += "client.git.nearestOriginCommit: ${it.message ?: it::class.simpleName}"
                return@onSuccess
            }
            gitValue(
                "client.git.changesSinceOrigin",
                "diff", "--no-ext-diff", "--binary", "${values["client.git.nearestOriginCommit"]}..HEAD",
            )
        }
        .onFailure { failures += "client.git.origin: ${it.message ?: it::class.simpleName}" }

    gitValue("client.git.localChanges", "diff", "--no-ext-diff", "--binary", "HEAD")
    gitValue("client.git.untrackedFiles", "ls-files", "--others", "--exclude-standard")
}

private fun runGit(
    vararg command: String,
    directory: File = File("."),
): Result<String> = runCatching {
    val process = ProcessBuilder(listOf("git") + command)
        .directory(directory)
        .redirectErrorStream(false)
        .start()
    val output = AtomicReference("")
    val error = AtomicReference("")
    val stdout = Thread { output.set(readLimited(process.inputStream, MaxBugDiagnosticValueLength)) }
    val stderr = Thread { error.set(readLimited(process.errorStream, 4_000)) }
    stdout.start()
    stderr.start()
    if (!process.waitFor(3, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        throw IllegalStateException("git command timed out")
    }
    stdout.join(500)
    stderr.join(500)
    if (process.exitValue() != 0) {
        throw IllegalStateException(error.get().trim().take(500).ifBlank { "git failed" })
    }
    output.get().trim().take(MaxBugDiagnosticValueLength)
}

private fun readLimited(input: InputStream, limit: Int): String {
    val output = ByteArrayOutputStream(minOf(limit, 8_192))
    val buffer = ByteArray(8_192)
    var total = 0
    input.use {
        while (true) {
            val read = it.read(buffer)
            if (read < 0) break
            if (total < limit) {
                val amount = minOf(read, limit - total)
                output.write(buffer, 0, amount)
            }
            total += read
        }
    }
    return output.toString(StandardCharsets.UTF_8)
}
