package bugreproducer

import kmpworkshop.common.BugImageAttachment
import kmpworkshop.common.ClientBugDiagnostics
import kmpworkshop.common.ClientBugReport
import kotlinx.serialization.json.Json
import workshop.adminaccess.ServerBugDiagnostics
import workshop.adminaccess.ServerState
import workshop.adminaccess.StoredClientBugReport
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ReportsTest {
    @Test
    fun `loads unknown fields sorts newest first and keeps malformed files visible`() {
        val root = Files.createTempDirectory("bug-reports")
        val directory = root.resolve("client_bug_reports").also { it.createDirectories() }
        val older = report("older", "2025-01-01T00:00:00Z")
        val newer = report("newer", "2025-01-02T00:00:00Z")
        directory.resolve("older.json").writeText(Json.encodeToString(older))
        directory.resolve("newer.json").writeText(
            Json.encodeToString(newer).dropLast(1) + ",\"futureField\":true}",
        )
        directory.resolve("broken.json").writeText("{ definitely not JSON")

        val result = loadClientBugReports(root)

        assertEquals(listOf("newer", "older"), result.reports.map { it.report.clientReport.description })
        assertEquals(listOf("broken.json"), result.malformed.map { it.path.fileName.toString() })
    }

    @Test
    fun `decodes persisted png attachments`() {
        val output = ByteArrayOutputStream()
        val image = BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB)
        check(ImageIO.write(image, "png", output))
        val attachment = BugImageAttachment("screen.png", "image/png", Base64.getEncoder().encodeToString(output.toByteArray()))

        val decoded = decodeBugImage(attachment).getOrThrow()

        assertEquals(2, decoded.image.width)
        assertEquals(3, decoded.image.height)
        assertTrue(decoded.bytes.isNotEmpty())
    }

    private fun report(description: String, receivedAt: String) = StoredClientBugReport(
        clientReport = ClientBugReport(
            description = description,
            diagnostics = ClientBugDiagnostics(),
            createdAt = Instant.parse(receivedAt),
        ),
        serverDiagnostics = ServerBugDiagnostics(emptyMap(), emptyList()),
        serverState = ServerState(),
        receivedAt = Instant.parse(receivedAt),
    )
}
