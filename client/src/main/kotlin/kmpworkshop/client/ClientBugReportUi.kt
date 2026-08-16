@file:OptIn(ExperimentalComposeUiApi::class)

package kmpworkshop.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kmpworkshop.common.*
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

@Composable
internal fun ClientBugReportPage(
    draft: ClientBugReportDraft,
    settings: ClientSettings,
    onDraftChange: (ClientBugReportDraft) -> Unit,
    onSubmit: suspend (ClientBugReport) -> ClientBugReportSubmissionResult,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
) {
    var diagnostics by remember { mutableStateOf<ClientBugDiagnostics?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var resetStep by remember { mutableStateOf(0) }
    var latestDraftForActions by remember { mutableStateOf(draft) }
    val scope = rememberCoroutineScope()

    SideEffect {
        latestDraftForActions = draft
    }

    fun changeDraft(transform: (ClientBugReportDraft) -> ClientBugReportDraft) {
        val updatedDraft = transform(latestDraftForActions)
        latestDraftForActions = updatedDraft
        onDraftChange(updatedDraft)
    }

    LaunchedEffect(Unit) {
        diagnostics = withContext(Dispatchers.IO) {
            runCatching { collectClientBugDiagnostics(settings) }
                .getOrElse { ClientBugDiagnostics(failures = listOf("client diagnostics: ${it.message}")) }
        }
    }

    fun addAttachments(results: List<Result<BugImageAttachment>>) {
        val failures = results.mapNotNull { it.exceptionOrNull() }
        if (failures.isNotEmpty()) {
            status = failures.first().message ?: "Could not add that image."
        }
        val additions = results.mapNotNull { it.getOrNull() }
        val attachments = (latestDraftForActions.attachments + additions).take(MaxBugAttachmentCount)
        if (attachmentBytes(attachments) > MaxBugAttachmentTotalBytes) {
            status = "The image attachments are too large in total."
        } else if (additions.isNotEmpty()) {
            changeDraft { it.copy(attachments = attachments) }
            if (failures.isEmpty()) status = null
        }
    }

    val window = LocalAwtWindow.current
    val previousDropTarget = remember(window) { window?.dropTarget }
    val latestDraft = rememberUpdatedState(latestDraftForActions)
    val latestAddAttachments = rememberUpdatedState<(List<Result<BugImageAttachment>>) -> Unit>(::addAttachments)
    DisposableEffect(window) {
        val dropTarget = runCatching {
            val listener = object : DropTargetAdapter() {
                override fun drop(event: DropTargetDropEvent) {
                    var accepted = false
                    try {
                        val transferable = event.transferable
                        event.acceptDrop(DnDConstants.ACTION_COPY)
                        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                            val files = (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                                .orEmpty()
                                .filterIsInstance<File>()
                                .take(MaxBugAttachmentCount - latestDraft.value.attachments.size)
                            latestAddAttachments.value(files.mapIndexed { index, file ->
                                imageAttachmentFromFile(file, latestDraft.value.attachments.size + index)
                            })
                            accepted = true
                        } else if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                            val image = transferable.getTransferData(DataFlavor.imageFlavor) as? Image
                            if (image != null) {
                                latestAddAttachments.value(
                                    listOf(imageAttachmentFromImage(image, latestDraft.value.attachments.size)),
                                )
                                accepted = true
                            }
                        }
                    } catch (failure: Throwable) {
                        latestAddAttachments.value(listOf(Result.failure(failure)))
                    } finally {
                        event.dropComplete(accepted)
                    }
                }
            }
            if (window == null) null else DropTarget(window, DnDConstants.ACTION_COPY, listener, true).also {
                window.dropTarget = it
            }
        }.getOrNull()
        onDispose {
            if (dropTarget != null && window?.dropTarget === dropTarget) window.dropTarget = previousDropTarget
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.V &&
                    (event.isCtrlPressed || event.isMetaPressed)
                ) {
                    pasteImage(latestDraftForActions.attachments.size) { latestAddAttachments.value(listOf(it)) }
                    true
                } else {
                    false
                }
            }
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Report Bug", style = MaterialTheme.typography.h4, fontWeight = FontWeight.SemiBold)
                Text(
                    "Tell us what happened. Diagnostics are collected automatically and sent with this report.",
                    color = Color(0xFF5F6368),
                )
            }
            OutlinedButton(onClick = onDismiss, enabled = !submitting) { Text("Close") }
        }
        Divider(color = Color(0xFFE4E7EB))

        Text("Description", style = MaterialTheme.typography.h6, fontWeight = FontWeight.SemiBold)
        TextField(
            value = draft.description,
            onValueChange = { description ->
                changeDraft { it.copy(description = description.take(MaxBugDescriptionLength)) }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).testTag("bug-description"),
            placeholder = { Text("What went wrong?") },
        )
        Text(
            "${draft.description.length} / $MaxBugDescriptionLength characters",
            color = Color(0xFF5F6368),
            style = MaterialTheme.typography.caption,
        )

        Text("Images", style = MaterialTheme.typography.h6, fontWeight = FontWeight.SemiBold)
        Text("Drop an image here or paste one with Ctrl/Cmd+V. Images are converted to PNG.", color = Color(0xFF5F6368))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    pasteImage(latestDraftForActions.attachments.size) { latestAddAttachments.value(listOf(it)) }
                },
                enabled = draft.attachments.size < MaxBugAttachmentCount,
                modifier = Modifier.testTag("paste-bug-image-button"),
            ) { Text("Paste image") }
            Text(
                "${draft.attachments.size} / $MaxBugAttachmentCount attached",
                modifier = Modifier.alignByBaseline(),
                color = Color(0xFF5F6368),
            )
        }
        draft.attachments.forEachIndexed { index, attachment ->
            Row(
                Modifier.fillMaxWidth().background(Color(0xFFF5F7FB)).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(attachment.fileName, Modifier.weight(1f))
                Text("${attachmentBytes(listOf(attachment)) / 1024} KB", color = Color(0xFF5F6368))
                TextButton(onClick = {
                    changeDraft {
                        it.copy(attachments = it.attachments.filterIndexed { i, _ -> i != index })
                    }
                }) { Text("Remove") }
            }
        }

        Text("Included information", style = MaterialTheme.typography.h6, fontWeight = FontWeight.SemiBold)
        Text(
            "The report includes your client OS/JVM/settings, repository commit and changes, " +
                "the current server state, and server diagnostics. Keep in mind that repository " +
                "changes may contain sensitive text.",
            color = Color(0xFF5F6368),
        )
        diagnostics?.let { collected ->
            Text("${collected.values.size} diagnostic snippets collected.", color = Color(0xFF2E7D32))
            if (collected.failures.isNotEmpty()) {
                Text(
                    "${collected.failures.size} snippets could not be collected; the report can still be sent.",
                    color = Color(0xFF9A6700),
                )
            }
        } ?: Text("Collecting diagnostics…", color = Color(0xFF5F6368))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = !submitting && diagnostics != null,
                onClick = {
                    val currentDiagnostics = diagnostics ?: return@Button
                    submitting = true
                    status = null
                    scope.launch {
                        try {
                            when (val result = onSubmit(ClientBugReport(
                                description = draft.description,
                                attachments = draft.attachments,
                                diagnostics = currentDiagnostics,
                                createdAt = Clock.System.now(),
                            ))) {
                                ClientBugReportSubmissionResult.Accepted -> {
                                    onReset()
                                    status = "Bug report sent. Thank you!"
                                }
                                ClientBugReportSubmissionResult.AdminUiNotConnected ->
                                    status = "The AdminUI is not connected. Your draft is still saved."
                                is ClientBugReportSubmissionResult.Rejected ->
                                    status = "Could not send the report: ${result.reason}"
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            status = "Could not send the report: ${failure.message ?: failure::class.simpleName}"
                        } finally {
                            submitting = false
                        }
                    }
                },
                modifier = Modifier.testTag("submit-bug-report-button"),
            ) { Text(if (submitting) "Sending…" else "Send report") }
            OutlinedButton(
                enabled = !submitting,
                onClick = {
                    when (resetStep) {
                        0 -> resetStep = 1
                        1 -> resetStep = 2
                        else -> {
                            resetStep = 0
                            onReset()
                            status = "Bug report reset."
                        }
                    }
                },
                modifier = Modifier.testTag("reset-bug-report-button"),
            ) {
                Text(when (resetStep) {
                    0 -> "Reset bug report"
                    1 -> "Are you sure?"
                    else -> "Very very sure"
                })
            }
        }
        status?.let { Text(it, color = if (it.startsWith("Bug report sent")) Color(0xFF2E7D32) else Color(0xFF9A6700)) }
    }
}

private fun pasteImage(
    existingCount: Int,
    onResult: (Result<BugImageAttachment>) -> Unit,
) {
    runCatching {
        val image = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.imageFlavor) as? Image
            ?: error("There is no image in the clipboard.")
        imageAttachmentFromImage(image, existingCount)
    }.getOrElse { Result.failure<BugImageAttachment>(it) }
        .let(onResult)
}
