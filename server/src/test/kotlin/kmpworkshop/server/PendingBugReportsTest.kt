package kmpworkshop.server

import kmpworkshop.common.WorkshopStage
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import workshop.adminaccess.BugType
import workshop.adminaccess.PendingBug
import workshop.adminaccess.ReportedBug
import workshop.adminaccess.ServerState
import workshop.adminaccess.StageChangeEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Clock

class PendingBugReportsTest {
    @Test
    fun `confirms the saved bug when receiving the next and waits when the queue is empty`() = runTest {
        val bugs = PendingBugReports()
        val first = serverBug("first")
        val second = serverBug("second")
        val third = serverBug("third")
        bugs.add(first)
        bugs.add(second)

        assertEquals(first, bugs.receiveNext())
        assertEquals(first, bugs.receiveNext("wrong-id"))
        assertEquals(second, bugs.receiveNext(first.report.id))
        val waiting = backgroundScope.async { bugs.receiveNext(second.report.id) }
        testScheduler.runCurrent()
        assertFalse(waiting.isCompleted)
        bugs.add(third)
        assertEquals(third, waiting.await())
    }

    @Test
    fun `server event errors stay queued until an admin acknowledges them`() = runTest {
        val bugs = PendingBugReports()
        val state = ServerState()
        val event = StageChangeEvent(WorkshopStage.Registration)

        reportError(state, event, bugs)

        val queued = bugs.receiveNext() as PendingBug.Server
        assertEquals(state, queued.report.initialState)
        assertEquals(BugType.Error(event), queued.report.type)
        assertEquals(queued, bugs.receiveNext())
    }

    private fun serverBug(id: String) = PendingBug.Server(
        ReportedBug(id, ServerState(), BugType.Error(StageChangeEvent(WorkshopStage.Registration)), Clock.System.now())
    )
}
