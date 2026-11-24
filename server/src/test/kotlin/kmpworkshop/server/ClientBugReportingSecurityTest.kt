package kmpworkshop.server

import kmpworkshop.common.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import workshop.adminaccess.Participant
import workshop.adminaccess.ServerState
import workshop.adminaccess.PendingBug
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.*
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock

class ClientBugReportingSecurityTest {
    @Test
    fun `accepts a 4K-sized image`(): Unit = runBlocking(Dispatchers.Default) {
        val report = report(image = png(3840, 2160))
        assertNull(validateClientBugReport(report))
    }

    @Test
    fun `rejects path traversal and non-image attachments`(): Unit = runBlocking(Dispatchers.Default) {
        assertNotNull(validateClientBugReport(report(
            image = png(1, 1),
            attachment = BugImageAttachment("../../escape", "image/png", reportImage(png(1, 1))),
        )))
        assertNotNull(validateClientBugReport(report(
            image = png(1, 1),
            attachment = BugImageAttachment("image.svg", "image/svg+xml", "<svg>"),
        )))
    }

    @Test
    fun `rejects malformed attachment data`(): Unit = runBlocking(Dispatchers.Default) {
        assertEquals("An attachment is not valid base64 data.", validateClientBugReport(report(
            image = png(1, 1),
            attachment = BugImageAttachment("image.png", "image/png", "not-base64"),
        )))
    }

    @Test
    fun `checks the per-attachment encoded limit before base64 decoding`() = runTest {
        val oversized = BugImageAttachment("large.png", "image/png", "!".repeat(MaxBugAttachmentEncodedLength + 1))
        assertEquals("An image attachment is too large.", validateClientBugReport(report(attachment = oversized)))
    }

    @Test
    fun `checks the total encoded limit before decoding any attachment`() = runTest {
        val large = BugImageAttachment("large.png", "image/png", "!".repeat(MaxBugAttachmentEncodedLength))
        val extra = BugImageAttachment("extra.png", "image/png", "!".repeat(16))
        val report = report().copy(attachments = listOf(large, large, extra))

        assertEquals("The image attachments are too large in total.", validateClientBugReport(report))
    }

    @Test
    fun `counts diagnostic values and failures toward the field limit`() = runTest {
        val values = mapOf("client.git.repository" to "/workshop")
        val failures = List(MaxBugDiagnosticFieldCount) { "diagnostic unavailable" }
        val report = report().copy(diagnostics = ClientBugDiagnostics(values, failures))

        assertEquals("Too many diagnostic fields.", validateClientBugReport(report))
    }

    @Test
    fun `counts diagnostic keys values and failures toward the total length limit`() = runTest {
        val key = "client.git.localChanges"
        val failure = "git status failed"
        val value = "a".repeat(MaxBugDiagnosticTotalLength - key.length - failure.length)
        val diagnostics = ClientBugDiagnostics(mapOf(key to value), listOf(failure))
        val report = report().copy(attachments = emptyList(), diagnostics = diagnostics)

        assertNull(validateClientBugReport(report))
        assertEquals(
            "The diagnostic fields are too large in total.",
            validateClientBugReport(report.copy(diagnostics = diagnostics.copy(failures = listOf("$failure!")))),
        )
    }

    @Test
    fun `rejects unsafe dimensions before decoding pixel data`(): Unit = runBlocking(Dispatchers.Default) {
        val report = report( image = pngHeader(MaxBugImagePixels + 1, 1))

        assertEquals("An image attachment has unsafe dimensions.", validateClientBugReport(report))
    }

    @Test
    fun `requires a registered api key and queues reports without an admin connection`(): Unit = runTest {
        val key = ApiKey("known")
        val state = ServerState(participants = listOf(Participant("Ada", key)))
        val reports = PendingBugReports()
        assertEquals(
            ClientBugReportSubmissionResult.Rejected("The client API key is not registered."),
            submitClientBugReport(ApiKey("unknown"), report(), state, reports),
        )
        assertEquals(
            ClientBugReportSubmissionResult.AdminUiNotConnected,
            submitClientBugReport(key, report(), state, null),
        )
        assertEquals(ClientBugReportSubmissionResult.Accepted, submitClientBugReport(key, report(), state, reports))
        val queued = reports.receiveNext() as PendingBug.Client
        assertEquals("Something went wrong", queued.report.clientReport.description)
        assertEquals(queued, reports.receiveNext("wrong-id"))
        assertEquals(queued, reports.receiveNext())
    }

    private fun report(
        image: ByteArray = png(1, 1),
        attachment: BugImageAttachment? = null,
    ): ClientBugReport {
        val imageAttachment = attachment ?: BugImageAttachment(
            fileName = "image.png",
            mimeType = "image/png",
            dataBase64 = reportImage(image),
        )
        return ClientBugReport("Something went wrong", listOf(imageAttachment), ClientBugDiagnostics(), Clock.System.now())
    }

    private fun reportImage(image: ByteArray): String = Base64.getEncoder().encodeToString(image)

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().use { output ->
        check(ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output))
        output.toByteArray()
    }

    private fun pngHeader(width: Int, height: Int): ByteArray = ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { png ->
            png.writeLong(0x89504E470D0A1A0AuL.toLong())
            val header = ByteArrayOutputStream().use { headerOutput ->
                DataOutputStream(headerOutput).use { headerData ->
                    headerData.writeBytes("IHDR")
                    headerData.writeInt(width)
                    headerData.writeInt(height)
                    headerData.writeByte(8)
                    headerData.writeByte(2)
                    headerData.writeByte(0)
                    headerData.writeByte(0)
                    headerData.writeByte(0)
                }
                headerOutput.toByteArray()
            }
            png.writeInt(header.size - 4)
            png.write(header)
            png.writeInt(CRC32().apply { update(header) }.value.toInt())
        }
        output.toByteArray()
    }
}
