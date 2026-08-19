package kmpworkshop.server

import kmpworkshop.common.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import workshop.adminaccess.ServerBugDiagnostics
import workshop.adminaccess.ServerState
import workshop.adminaccess.StoredClientBugReport
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.time.Clock

internal const val MaxBugImagePixels = 40_000_000

internal fun ServerState.acceptsBugReportFrom(key: ApiKey): Boolean =
    (participants + deactivatedParticipants).any { it.apiKey == key }

internal fun validateClientBugReport(report: ClientBugReport): String? {
    if (report.description.length > MaxBugDescriptionLength) {
        return "The bug description is too long."
    }
    if (report.attachments.size > MaxBugAttachmentCount) {
        return "Too many image attachments."
    }

    var totalBytes = 0
    for (attachment in report.attachments) {
        if (attachment.fileName.length > 200 || attachment.fileName.any { it == '/' || it == '\\' }) {
            return "An attachment has an invalid name."
        }
        if (attachment.mimeType != "image/png") {
            return "Only PNG image attachments are supported."
        }
        val bytes = runCatching { Base64.getDecoder().decode(attachment.dataBase64) }
            .getOrElse { return "An attachment is not valid base64 data." }
        if (bytes.size > MaxBugAttachmentBytes) return "An image attachment is too large."
        totalBytes += bytes.size
        if (totalBytes > MaxBugAttachmentTotalBytes) return "The image attachments are too large in total."

        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }
            .getOrElse { return "An image attachment could not be decoded." }
            ?: return "An image attachment could not be decoded."
        if (image.width <= 0 || image.height <= 0 || image.width.toLong() * image.height > MaxBugImagePixels) {
            return "An image attachment has unsafe dimensions."
        }
    }
    return null
}

internal fun buildStoredClientBugReport(
    report: ClientBugReport,
    serverState: ServerState,
    provenance: ServerProvenance = ServerProvenance.load(),
): StoredClientBugReport = StoredClientBugReport(
    clientReport = report,
    serverDiagnostics = collectServerBugDiagnostics(provenance),
    serverState = serverState,
    receivedAt = Clock.System.now(),
)

private fun collectServerBugDiagnostics(provenance: ServerProvenance): ServerBugDiagnostics {
    val values = linkedMapOf<String, String>()
    val failures = provenance.failures.toMutableList()
    fun collect(name: String, value: () -> String) {
        runCatching { value() }
            .onSuccess { values[name] = it.take(MaxBugDiagnosticValueLength) }
            .onFailure { failures += "$name: ${it.message ?: it::class.simpleName}" }
    }
    collect("server.os") { System.getProperty("os.name") }
    collect("server.os.version") { System.getProperty("os.version") }
    collect("server.jvm") { System.getProperty("java.runtime.version") }
    provenance.commit?.let { values["server.checkedOutCommit"] = it.take(MaxBugDiagnosticValueLength) }
    provenance.changes?.let { values["server.changes"] = it }
    values["server.changesTruncated"] = provenance.changesTruncated.toString()
    provenance.changesSha256?.let { values["server.changesSha256"] = it }
    provenance.untrackedChanges?.let { values["server.untrackedChanges"] = it }
    values["server.untrackedChangesTruncated"] = provenance.untrackedChangesTruncated.toString()
    provenance.untrackedChangesSha256?.let { values["server.untrackedChangesSha256"] = it }
    return ServerBugDiagnostics(values, failures)
}

internal fun submitClientBugReport(
    key: ApiKey,
    report: ClientBugReport,
    serverState: ServerState,
    clientBugReports: MutableSharedFlow<StoredClientBugReport>,
): ClientBugReportSubmissionResult {
    if (!serverState.acceptsBugReportFrom(key)) {
        return ClientBugReportSubmissionResult.Rejected("The client API key is not registered.")
    }
    validateClientBugReport(report)?.let { return ClientBugReportSubmissionResult.Rejected(it) }
    if (clientBugReports.subscriptionCount.value == 0) {
        return ClientBugReportSubmissionResult.AdminUiNotConnected
    }
    val storedReport = buildStoredClientBugReport(report, serverState)
    return if (clientBugReports.tryEmit(storedReport)) {
        ClientBugReportSubmissionResult.Accepted
    } else {
        ClientBugReportSubmissionResult.Rejected("The connected AdminUI could not receive the report.")
    }
}

internal data class ServerProvenance(
    val commit: String?,
    val changes: String?,
    val changesTruncated: Boolean,
    val changesSha256: String?,
    val untrackedChanges: String?,
    val untrackedChangesTruncated: Boolean,
    val untrackedChangesSha256: String?,
    val failures: List<String>,
) {
    companion object {
        fun load(): ServerProvenance = runCatching {
            val json = Json.decodeFromString<ServerProvenanceFile>(
                ServerProvenance::class.java.classLoader
                    .getResourceAsStream("server-provenance.json")
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: error("server-provenance.json is not available"),
            )
            ServerProvenance(
                commit = json.commit,
                changes = json.changes,
                changesTruncated = json.changesTruncated,
                changesSha256 = json.changesSha256,
                untrackedChanges = json.untrackedChanges,
                untrackedChangesTruncated = json.untrackedChangesTruncated,
                untrackedChangesSha256 = json.untrackedChangesSha256,
                failures = json.failures,
            )
        }.getOrElse {
            ServerProvenance(
                commit = null,
                changes = null,
                changesTruncated = false,
                changesSha256 = null,
                untrackedChanges = null,
                untrackedChangesTruncated = false,
                untrackedChangesSha256 = null,
                failures = listOf("server provenance: ${it.message ?: it::class.simpleName}"),
            )
        }
    }
}

@Serializable
private data class ServerProvenanceFile(
    val commit: String? = null,
    val changes: String? = null,
    val changesTruncated: Boolean = false,
    val changesSha256: String? = null,
    val untrackedChanges: String? = null,
    val untrackedChangesTruncated: Boolean = false,
    val untrackedChangesSha256: String? = null,
    val failures: List<String> = emptyList(),
)
