package com.kotlinworkshop.test

import org.opentest4j.AssertionFailedError
import org.opentest4j.FileInfo
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Compares this string with a snapshot under `serverAndClientTest/src/test/resources`.
 * Pass [snapshotPath] as an explicit string literal so it remains navigable with Cmd/Ctrl+click in the IDE.
 * A missing snapshot is created; a mismatch is reported as an IDE-friendly file comparison.
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
            "If the new output is correct, accept it to update the snapshot on disk.\nActual:\n$this",
        FileInfo(path, expectedBytes),
        this,
    )
}

/** Finds this module's test resources from either Gradle's or the IDE's working directory. */
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
