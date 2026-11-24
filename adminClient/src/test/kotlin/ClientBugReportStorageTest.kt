import kmpworkshop.common.ClientBugDiagnostics
import kmpworkshop.common.ClientBugReport
import workshop.adminaccess.ServerState
import workshop.adminaccess.ServerBugDiagnostics
import workshop.adminaccess.StoredClientBugReport
import workshop.adminaccess.PendingBug
import workshop.adminaccess.ReportedBug
import workshop.adminaccess.BugType
import workshop.adminaccess.StageChangeEvent
import kmpworkshop.common.WorkshopStage
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
            id = "0c64028a-f514-488b-a4fc-9e45a02d533e",
            clientReport = ClientBugReport("It broke", diagnostics = ClientBugDiagnostics(), createdAt = Clock.System.now()),
            serverDiagnostics = ServerBugDiagnostics(emptyMap(), emptyList()),
            serverState = ServerState(),
            receivedAt = Clock.System.now(),
        )

        persistPendingBugLocally(PendingBug.Client(report), directory)
        persistPendingBugLocally(PendingBug.Client(report), directory)

        val files = Files.list(directory.resolve("client_bug_reports")).use { it.toList() }
        assertEquals(1, files.size)
        assertEquals(true, Files.readString(files.single()).contains("It broke"))
    }

    @Test
    fun `stores server bugs once in the admin bug directory`() {
        val directory = createTempDirectory("server-bugs")
        val bug = ReportedBug(
            id = "66d65381-2fd8-4ea6-aa46-d9f7792719e1",
            initialState = ServerState(),
            type = BugType.Error(StageChangeEvent(WorkshopStage.Registration)),
            moment = Clock.System.now(),
        )

        persistPendingBugLocally(PendingBug.Server(bug), directory)
        persistPendingBugLocally(PendingBug.Server(bug), directory)

        val files = Files.list(directory).use { it.toList() }
        assertEquals(1, files.size)
        assertEquals(true, Files.readString(files.single()).contains("66d65381-2fd8-4ea6-aa46-d9f7792719e1"))
    }
}
