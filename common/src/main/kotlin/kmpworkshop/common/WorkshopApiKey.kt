package kmpworkshop.common

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Base64

const val WorkshopInvitationCodeHeader = "X-Workshop-Invitation-Code"

/** Trims surrounding whitespace and accepts codes that fit on one line in the credentials file. */
fun normalizeWorkshopInvitationCode(value: String?): String? =
    value?.trim()?.takeIf { it.isNotEmpty() && '\r' !in it && '\n' !in it }

/** Encodes the UTF-8 invitation code as an ASCII value for the WebSocket handshake. */
fun workshopInvitationCodeHeaderValue(invitationCode: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(invitationCode.encodeToByteArray())

private val savedClientCredentials by lazy { readClientCredentials(Path.of("")) }

val clientApiKey: String? by lazy {
    System.getenv("WORKSHOP_API_KEY")?.takeIf { it.isNotBlank() } ?: savedClientCredentials?.apiKey
}

val clientInvitationCode: String? by lazy {
    normalizeWorkshopInvitationCode(System.getenv("WORKSHOP_INVITATION_CODE"))
        ?: savedClientCredentials?.invitationCode
}

internal class ClientCredentials(val apiKey: String?, val invitationCode: String?)

internal fun readClientCredentials(workingDirectory: Path): ClientCredentials? {
    val file = clientApiKeyFile(workingDirectory) ?: return null
    val lines = try {
        Files.readAllLines(file)
    } catch (_: NoSuchFileException) {
        return null
    }
    return ClientCredentials(
        apiKey = lines.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() },
        invitationCode = normalizeWorkshopInvitationCode(lines.getOrNull(1)),
    )
}

/** Saves the participant's API key and invitation code together in the workshop checkout. */
fun saveClientCredentials(key: ApiKey, invitationCode: String, workingDirectory: Path = Path.of("")) {
    val code = requireNotNull(normalizeWorkshopInvitationCode(invitationCode)) {
        "The workshop invitation code must be a non-blank, single-line value."
    }
    val file = checkNotNull(clientApiKeyFile(workingDirectory)) { "Run registration from the workshop checkout." }
    val temporary = Files.createTempFile(file.parent, ".workshop-api-key.", ".tmp")
    try {
        Files.writeString(temporary, "${key.stringRepresentation}\n$code\n")
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
