package kmpworkshop.client

import kmpworkshop.common.WorkshopStage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkshopRunGateTest {
    @Test
    fun `allows one attempt per stage and successful reload generation`() {
        val gate = WorkshopRunGate(WorkshopStage.SumOfTwoIntsSlow)

        assertTrue(gate.startAttempt())
        assertFalse(gate.startAttempt())

        gate.successfulReload()
        assertTrue(gate.startAttempt())
        assertFalse(gate.startAttempt())

        gate.enterStage(WorkshopStage.SumOfTwoIntsFast)
        assertTrue(gate.startAttempt())
    }

    @Test
    fun `returning to a stage does not bypass its generation gate`() {
        val gate = WorkshopRunGate(WorkshopStage.SumOfTwoIntsSlow)
        assertTrue(gate.startAttempt())
        gate.enterStage(WorkshopStage.SumOfTwoIntsFast)
        assertTrue(gate.startAttempt())
        gate.enterStage(WorkshopStage.SumOfTwoIntsSlow)
        assertFalse(gate.startAttempt())
    }
}
