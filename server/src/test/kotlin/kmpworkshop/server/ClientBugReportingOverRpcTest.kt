package kmpworkshop.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.http.URLProtocol
import io.ktor.http.encodedPath
import io.ktor.server.application.plugin
import io.ktor.server.websocket.WebSockets
import kmpworkshop.common.ApiKey
import kmpworkshop.common.BugImageAttachment
import kmpworkshop.common.ClientBugDiagnostics
import kmpworkshop.common.ClientBugReport
import kmpworkshop.common.ClientBugReportSubmissionResult
import kmpworkshop.common.MaxBugAttachmentBytes
import kmpworkshop.common.MaxBugDiagnosticValueLength
import kmpworkshop.common.WorkshopApiService
import kmpworkshop.common.WorkshopInvitationCodeHeader
import kmpworkshop.common.workshopInvitationCodeHeaderValue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import workshop.adminaccess.AdminAccess
import workshop.adminaccess.BugType
import workshop.adminaccess.Participant
import workshop.adminaccess.PendingBug
import workshop.adminaccess.ReportingParticipantIndexDiagnostic
import workshop.adminaccess.ReportedBug
import workshop.adminaccess.ServerState
import workshop.adminaccess.StageChangeEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ClientBugReportingOverRpcTest {
    @Test
    fun `a report at the attachment limit reaches the admin over real websockets`() = runBlocking {
        val key = ApiKey("participant-key")
        val password = "test-admin-password"
        val invitationCode = "test-invitation-code"
        val state = ServerState(participants = listOf(Participant("Other", ApiKey("other-key")), Participant("Ada", key)))
        val bugs = PendingBugReports()
        val server = rpcServer(
            invitationCode = invitationCode,
            port = 0,
            services = listOf<RpcService<*>>(
                rpcService<WorkshopApiService> { workshopService(flowOf(state), { error("Unexpected workshop event") }, bugs) },
                rpcService<AdminAccess> {
                    adminAccess(password, flowOf(state), { error("Unexpected admin event") }, flowOf(), bugs)
                },
            ),
        )
        val client = HttpClient(CIO) { installKrpc { connector { } } }

        try {
            server.startSuspend(wait = false)
            assertEquals(WorkshopMaxWebSocketFrameSize, server.application.plugin(WebSockets).maxFrameSize)
            withTimeout(45.seconds) {
                val boundPort = server.engine.resolvedConnectors().first().port
                fun connect() = client.rpc {
                    header(WorkshopInvitationCodeHeader, workshopInvitationCodeHeaderValue(invitationCode))
                    url {
                        protocol = URLProtocol.WS
                        host = "localhost"
                        port = boundPort
                        encodedPath = "rpc"
                    }
                    rpcConfig { serialization { json() } }
                }
                val participant = connect().withService<WorkshopApiService>()
                val admin = connect().withService<AdminAccess>()
                val waiting = async { admin.receiveNextBug(password, null) }
                delay(100.milliseconds)
                assertFalse(waiting.isCompleted)
                val report = largeReport()
                assertEquals(ClientBugReportSubmissionResult.Accepted, participant.submitClientBugReport(key, report))
                val stored = (waiting.await() as PendingBug.Client).report
                assertEquals(report, stored.clientReport)
                assertEquals(state, stored.serverState)
                assertEquals("1", stored.serverDiagnostics.values[ReportingParticipantIndexDiagnostic])
                assertEquals(stored.id, (admin.receiveNextBug(password, "wrong-id") as PendingBug.Client).report.id)

                val serverBug = ReportedBug(
                    "server-bug-id",
                    state,
                    BugType.Error(StageChangeEvent(kmpworkshop.common.WorkshopStage.Registration)),
                    Clock.System.now(),
                )
                val nextServerBug = serverBug.copy(id = "next-server-bug-id")
                bugs.add(PendingBug.Server(serverBug))
                bugs.add(PendingBug.Server(nextServerBug))
                assertEquals(PendingBug.Server(serverBug), admin.receiveNextBug(password, stored.id))
                assertEquals(PendingBug.Server(nextServerBug), admin.receiveNextBug(password, serverBug.id))
            }
        } finally {
            client.close()
            server.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 1_000)
        }
    }

    private fun largeReport(): ClientBugReport {
        // Exactly 30 MiB decoded and 12 extra encoded characters from per-image Base64 padding.
        val sizes = listOf(MaxBugAttachmentBytes - 2, MaxBugAttachmentBytes - 298, 100, 100, 100)
        val attachments = sizes.mapIndexed { index, size ->
            BugImageAttachment("image-$index.png", "image/png", Base64.getEncoder().encodeToString(pngWithText(size)))
        }
        val line = "+ println(\"Grüezi\")\n"
        val gitOutput = line.repeat(MaxBugDiagnosticValueLength / line.length + 1).take(MaxBugDiagnosticValueLength)
        val diagnostics = ClientBugDiagnostics(
            values = mapOf(
                "client.git.changesSinceOrigin" to gitOutput,
                "client.git.localChanges" to gitOutput,
                "client.git.untrackedFiles" to gitOutput,
            ),
        )
        return ClientBugReport("Puzzle stalled", attachments, diagnostics, Clock.System.now())
    }

    private fun pngWithText(size: Int): ByteArray {
        val image = ByteArrayOutputStream().use { output ->
            check(ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output))
            output.toByteArray()
        }
        val iendOffset = image.size - 12
        val textSize = size - image.size - 12
        val bytes = ByteArray(size)
        val png = ByteBuffer.wrap(bytes)
        png.put(image, 0, iendOffset)
        png.putInt(textSize)
        png.put("tEXtComment\u0000".encodeToByteArray())
        val textEnd = iendOffset + 8 + textSize
        bytes.fill('x'.code.toByte(), fromIndex = png.position(), toIndex = textEnd)
        png.position(textEnd)
        png.putInt(CRC32().apply { update(bytes, iendOffset + 4, 4 + textSize) }.value.toInt())
        png.put(image, iendOffset, 12)
        return bytes
    }
}
