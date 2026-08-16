package kmpworkshop.common

import kotlinx.serialization.Serializable
import kotlin.time.Instant

const val MaxBugDescriptionLength = 20_000
const val MaxBugAttachmentCount = 5
const val MaxBugAttachmentBytes = 15 * 1024 * 1024
const val MaxBugAttachmentTotalBytes = 30 * 1024 * 1024
const val MaxBugDiagnosticValueLength = 750_000

@Serializable
data class BugImageAttachment(
    val fileName: String,
    val mimeType: String,
    val dataBase64: String,
)

@Serializable
data class ClientBugDiagnostics(
    val values: Map<String, String> = emptyMap(),
    val failures: List<String> = emptyList(),
)

@Serializable
data class ClientBugReport(
    val description: String,
    val attachments: List<BugImageAttachment> = emptyList(),
    val diagnostics: ClientBugDiagnostics = ClientBugDiagnostics(),
    val createdAt: Instant,
)

@Serializable
data class ClientBugReportDraft(
    val description: String = "",
    val attachments: List<BugImageAttachment> = emptyList(),
)

@Serializable
sealed class ClientBugReportSubmissionResult {
    @Serializable
    data object Accepted : ClientBugReportSubmissionResult()

    @Serializable
    data object AdminUiNotConnected : ClientBugReportSubmissionResult()

    @Serializable
    data class Rejected(val reason: String) : ClientBugReportSubmissionResult()
}
