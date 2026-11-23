package kmpworkshop.common

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

val clientApiKey: String? by lazy {
    System.getenv("WORKSHOP_API_KEY")?.takeIf { it.isNotBlank() } ?: readClientApiKey(Path.of(""))
}

internal fun readClientApiKey(workingDirectory: Path): String? {
    val file = clientApiKeyFile(workingDirectory) ?: return null
    return try {
        Files.readString(file).trim().takeIf { it.isNotEmpty() }
    } catch (_: NoSuchFileException) {
        null
    }
}

/** Saves the registration key in the workshop checkout for the registration and desktop applications. */
fun saveClientApiKey(key: ApiKey, workingDirectory: Path = Path.of("")) {
    val file = checkNotNull(clientApiKeyFile(workingDirectory)) { "Run registration from the workshop checkout." }
    val temporary = Files.createTempFile(file.parent, ".workshop-api-key.", ".tmp")
    try {
        Files.writeString(temporary, key.stringRepresentation + "\n")
        try {
            Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, file, REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun clientApiKeyFile(workingDirectory: Path): Path? =
    generateSequence(workingDirectory.toAbsolutePath().normalize()) { it.parent }
        .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        ?.resolve(".workshop-api-key")
