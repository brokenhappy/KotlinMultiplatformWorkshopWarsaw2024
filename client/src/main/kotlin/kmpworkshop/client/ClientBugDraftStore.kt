package kmpworkshop.client

import kmpworkshop.common.ClientBugReportDraft
import kmpworkshop.common.MaxBugAttachmentBytes
import kmpworkshop.common.MaxBugAttachmentCount
import kmpworkshop.common.MaxBugDescriptionLength
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption

internal class ClientBugDraftStore(
    private val file: File = defaultDraftFile(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): ClientBugReportDraft = runCatching {
        val draft = json.decodeFromString<ClientBugReportDraft>(file.readText())
        require(draft.description.length <= MaxBugDescriptionLength)
        require(draft.attachments.size <= MaxBugAttachmentCount)
        require(draft.attachments.all { it.dataBase64.length <= MaxBugAttachmentBytes * 2 })
        draft
    }.getOrDefault(ClientBugReportDraft())

    fun save(draft: ClientBugReportDraft) {
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(json.encodeToString(draft))
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    fun clear() {
        runCatching { Files.deleteIfExists(file.toPath()) }
    }
}

private fun defaultDraftFile(): File = File(
    System.getProperty("user.home", "."),
    ".kmpworkshop/client-bug-report-draft.json",
)
