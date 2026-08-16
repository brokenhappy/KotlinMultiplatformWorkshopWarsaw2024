package kmpworkshop.server

import kmpworkshop.common.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import workshop.adminaccess.Participant
import workshop.adminaccess.ServerState
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClientBugReportingSecurityTest {
    @Test
    fun `accepts a 4K-sized image`() {
        val report = report(image = png(3840, 2160))
        assertNull(validateClientBugReport(report))
    }

    @Test
    fun `rejects path traversal and non-image attachments`() {
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
    fun `rejects malformed and oversized attachment data`() {
        assertNotNull(validateClientBugReport(report(
            image = png(1, 1),
            attachment = BugImageAttachment("image.png", "image/png", "not-base64"),
        )))
        val oversized = Base64.getEncoder().encodeToString(ByteArray(MaxBugAttachmentBytes + 1))
        assertNotNull(validateClientBugReport(report(
            image = png(1, 1),
            attachment = BugImageAttachment("image.png", "image/png", oversized),
        )))
    }

    @Test
    fun `requires a registered api key and an active admin stream`() = runTest {
        val key = ApiKey("known")
        val state = ServerState(participants = listOf(Participant("Ada", key)))
        val reports = MutableSharedFlow<workshop.adminaccess.StoredClientBugReport>(extraBufferCapacity = 1)
        assertEquals(
            ClientBugReportSubmissionResult.Rejected("The client API key is not registered."),
            submitClientBugReport(ApiKey("unknown"), report(), state, reports),
        )
        assertEquals(
            ClientBugReportSubmissionResult.AdminUiNotConnected,
            submitClientBugReport(key, report(), state, reports),
        )

        var received = 0
        val collector = backgroundScope.launch { reports.collect { received++ } }
        testScheduler.runCurrent()
        assertEquals(ClientBugReportSubmissionResult.Accepted, submitClientBugReport(key, report(), state, reports))
        testScheduler.runCurrent()
        assertEquals(1, received)
        collector.cancel()
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
        return ClientBugReport("Something went wrong", listOf(imageAttachment), ClientBugDiagnostics(), kotlin.time.Clock.System.now())
    }

    private fun reportImage(image: ByteArray): String = Base64.getEncoder().encodeToString(image)

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().use { output ->
        check(ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output))
        output.toByteArray()
    }
}
