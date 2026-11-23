package bugreproducer

import kmpworkshop.common.ApiKey
import kmpworkshop.common.ClientBugDiagnostics
import kmpworkshop.common.ClientBugReport
import workshop.adminaccess.Participant
import workshop.adminaccess.ReportingParticipantIndexDiagnostic
import workshop.adminaccess.ServerBugDiagnostics
import workshop.adminaccess.ServerState
import workshop.adminaccess.StoredClientBugReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class DiagnosticsTest {
    @Test
    fun `parses revisions settings and captured diffs`() {
        val result = parseReproductionDiagnostics(report(
            clientValues = mapOf(
                "client.git.checkedOutCommit" to "a".repeat(40),
                "client.git.localChanges" to "client patch",
                "client.git.localChangesTruncated" to "true",
                "client.settings" to "zoom=1.4",
                "client.git.untrackedFiles" to "new-file.kt",
            ),
            serverValues = mapOf(
                "server.checkedOutCommit" to "b".repeat(40),
                "server.changes" to "server patch",
                "server.untrackedChanges" to "untracked patch",
            ),
        )).getOrThrow()

        assertEquals("a".repeat(40), result.clientCommit)
        assertEquals(1.4f, result.clientSettings.zoom)
        assertEquals("client patch", result.clientLocalChanges)
        assertTrue(result.warnings.any { it.contains("untracked files") })
        assertTrue(result.warnings.any { it.contains("client local changes were truncated") })
    }

    @Test
    fun `rejects missing provenance and invalid settings`() {
        assertFailsWith<IllegalStateException> {
            parseReproductionDiagnostics(report(emptyMap(), emptyMap())).getOrThrow()
        }
        assertFailsWith<IllegalArgumentException> {
            parseReproductionDiagnostics(report(
                clientValues = validClientValues() + ("client.settings" to "zoom=NaN"),
                serverValues = validServerValues(),
            )).getOrThrow()
        }
    }

    @Test
    fun `extracts api key only from a usable source declaration`() {
        assertEquals(
            "historical-key",
            apiKeyFromAppliedClientSource("val clientApiKey: String? = \"historical-key\"").getOrThrow(),
        )
        assertTrue(apiKeyFromAppliedClientSource("val clientApiKey: String? = null").isFailure)
    }

    @Test
    fun `new reports identify the reporter in the server snapshot`() {
        val snapshot = ServerState(
            participants = listOf(Participant("Ada", ApiKey("active-key"))),
            deactivatedParticipants = listOf(Participant("Ada", ApiKey("reporter-key"))),
        )
        val report = report(validClientValues(), validServerValues()).copy(serverState = snapshot)
        assertNull(reportingParticipantApiKey(report))
        val diagnostics = report.serverDiagnostics.copy(
            values = report.serverDiagnostics.values + (ReportingParticipantIndexDiagnostic to "1"),
        )
        assertEquals("reporter-key", reportingParticipantApiKey(report.copy(serverDiagnostics = diagnostics)))
    }

    private fun report(
        clientValues: Map<String, String>,
        serverValues: Map<String, String>,
    ) = StoredClientBugReport(
        ClientBugReport("bug", diagnostics = ClientBugDiagnostics(clientValues), createdAt = Clock.System.now()),
        ServerBugDiagnostics(serverValues, emptyList()),
        ServerState(),
        Clock.System.now(),
    )

    private fun validClientValues() = mapOf(
        "client.git.checkedOutCommit" to "a".repeat(40),
        "client.git.localChanges" to "",
        "client.settings" to "zoom=1",
    )

    private fun validServerValues() = mapOf(
        "server.checkedOutCommit" to "b".repeat(40),
        "server.changes" to "",
        "server.untrackedChanges" to "",
    )
}
