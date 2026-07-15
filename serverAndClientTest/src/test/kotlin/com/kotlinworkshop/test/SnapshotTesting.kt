package com.kotlinworkshop.test

import org.intellij.lang.annotations.Language
import org.opentest4j.AssertionFailedError
import org.opentest4j.FileInfo
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Asserts that [this] matches the file-based snapshot at [snapshotPath].
 *
 * [snapshotPath] is an explicit, literal path chosen by the caller (e.g.
 * `"CoroutinePuzzleMessageSnapshotTest/some_case.txt"`) - relative to
 * `src/test/resources/snapshots/` in this module. It is intentionally *not* derived from the test name/[org.junit.jupiter.api.TestInfo]:
 * a literal path is something IntelliJ (and a human) can jump to directly, and it doesn't silently move/orphan
 * itself when a test gets renamed.
 *
 * - If the snapshot file doesn't exist yet, it is created with [this@assertMatchesSnapshot] as its content (bootstrapping a baseline),
 *   and the assertion passes.
 * - If it exists and its content differs from [this@assertMatchesSnapshot], this throws an [AssertionFailedError] whose `expected` and
 *   `actual` are [FileInfo] instances pointing at the *same* real snapshot file path (one wrapping the bytes
 *   currently on disk, the other wrapping the new [this@assertMatchesSnapshot] bytes). This is the mechanism IntelliJ's JUnit 5 runner
 *   uses to render a rich file diff with an "Accept" action that writes [this@assertMatchesSnapshot] directly into the snapshot file.
 */
fun String.assertMatchesSnapshot(snapshotPath: String) {
    val snapshotFile = testResourcesRoot().resolve(snapshotPath)
    val actualBytes = toByteArray(StandardCharsets.UTF_8)

    if (Files.notExists(snapshotFile)) {
        Files.createDirectories(snapshotFile.parent)
        Files.write(snapshotFile, actualBytes)
        return
    }

    val expectedBytes = Files.readAllBytes(snapshotFile)
    if (expectedBytes.contentEquals(actualBytes)) return

    val path = snapshotFile.toString()
    throw AssertionFailedError(
        "Snapshot mismatch for $snapshotPath (snapshot file: $path). " +
            "If the new output is correct, accept it to update the snapshot on disk.",
        FileInfo(path, expectedBytes),
        FileInfo(path, actualBytes),
    )
}

/**
 * Resolves `src/test/resources` inside the `serverAndClientTest` module, regardless of what the current working
 * directory happens to be when the test runs (Gradle's `test` task runs with the module directory as CWD, but IDE
 * test runners may differ). Walks upward from the current working directory looking for a `serverAndClientTest`
 * module - either because we're already inside it, or because it's a sibling/descendant-of-an-ancestor directory.
 *
 * [snapshotPath] is resolved against this directory, so it should start with `snapshots/...`, e.g.
 * `"snapshots/CoroutinePuzzleMessageSnapshotTest/some_case.txt"`.
 */
private fun testResourcesRoot(): Path {
    val startDir = Path.of("").toAbsolutePath()
    var dir: Path? = startDir
    while (dir != null) {
        val asModuleRoot = dir.takeIf { it.fileName?.toString() == "serverAndClientTest" && it.isGradleModuleRoot() }
        if (asModuleRoot != null) return asModuleRoot.resolve("src/test/resources")

        val siblingModule = dir.resolve("serverAndClientTest")
        if (siblingModule.isGradleModuleRoot()) return siblingModule.resolve("src/test/resources")

        dir = dir.parent
    }
    error("Could not locate the serverAndClientTest module directory, starting from $startDir")
}

private fun Path.isGradleModuleRoot(): Boolean =
    Files.exists(resolve("build.gradle.kts")) && Files.isDirectory(resolve("src/test/kotlin"))
