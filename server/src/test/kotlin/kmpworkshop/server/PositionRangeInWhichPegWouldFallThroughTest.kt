package kmpworkshop.server

import workshop.adminaccess.PegWidth
import workshop.adminaccess.SliderGapWidth
import workshop.adminaccess.SliderState
import workshop.adminaccess.positionRangeInWhichPegWouldFallThrough
import kotlin.test.assertEquals

class PositionRangeInWhichPegWouldFallThroughTest {
//    @Test
    fun foo() {
        val someValue = 0.0
        fun endingAt(rangeStart: Double) = (rangeStart - ((SliderGapWidth - PegWidth) * 2 / 3))..rangeStart
        assertEquals(
            endingAt(0.5),
            SliderState(
                gapOffset = 0.0,
                position = someValue,
            ).positionRangeInWhichPegWouldFallThrough(0.0)
        )
        assertEquals(
            endingAt(0.0),
            SliderState(
                gapOffset = 1.0,
                position = someValue,
            ).positionRangeInWhichPegWouldFallThrough(0.0)
        )
        assertEquals(
            endingAt(1.0),
            SliderState(
                gapOffset = 0.0,
                position = someValue,
            ).positionRangeInWhichPegWouldFallThrough(1.0)
        )
        assertEquals(
            endingAt(0.5),
            SliderState(
                gapOffset = 1.0,
                position = someValue,
            ).positionRangeInWhichPegWouldFallThrough(1.0)
        )
        assertEquals(
            endingAt(.75),
            SliderState(
                gapOffset = 0.0,
                position = someValue,
            ).positionRangeInWhichPegWouldFallThrough(0.5)
        )
    }
}