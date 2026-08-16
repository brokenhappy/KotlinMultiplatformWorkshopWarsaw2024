import kmpworkshop.common.ClientBugDiagnostics
import kmpworkshop.common.ClientBugReport
import workshop.adminaccess.ServerState
import workshop.adminaccess.ServerBugDiagnostics
import workshop.adminaccess.StoredClientBugReport
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class ClientBugReportStorageTest {
    @Test
    fun `stores reports below the client bug report directory`() {
        val directory = createTempDirectory("client-bugs")
        val report = StoredClientBugReport(
            clientReport = ClientBugReport("It broke", diagnostics = ClientBugDiagnostics(), createdAt = Clock.System.now()),
            serverDiagnostics = ServerBugDiagnostics(emptyMap(), emptyList()),
            serverState = ServerState(),
            receivedAt = Clock.System.now(),
        )

        persistClientBugReportLocally(report, directory)

        val files = Files.list(directory.resolve("client_bug_reports")).use { it.toList() }
        assertEquals(1, files.size)
        assertEquals(true, Files.readString(files.single()).contains("It broke"))
    }
}
