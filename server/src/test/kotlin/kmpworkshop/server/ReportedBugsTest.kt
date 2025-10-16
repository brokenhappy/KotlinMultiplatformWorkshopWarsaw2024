package kmpworkshop.server

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import workshop.adminaccess.after
import java.io.File
import kotlin.test.assertEquals

class ReportedBugsTest {
    @Test
    fun `check that all reported determinism bugs are fixed`() {
        for (bug in loadReportedBugs()) {
            val type = bug.type as? BugType.IndeterministicEvent ?: continue
            val expectedResult = bug.initialState.after(type.event, onSoundEvent = {}).droppingScheduledEventTimes()
            repeat(100) {
                assertEquals(
                    expectedResult,
                    bug.initialState.after(type.event, onSoundEvent = {}).droppingScheduledEventTimes(),
                    "Expected result to be the same each time, but it changed at some point :("
                )
            }
        }
    }

    @Test
    fun `check that all reported errors are fixed`() {
        for (bug in loadReportedBugs()) {
            (bug.type as? BugType.Error)
                ?.let { bug.initialState.after(it.failingEvent, onSoundEvent = {}) }
        }
    }
}

private fun loadReportedBugs(): List<ReportedBug> = (bugDirectory ?: fail("Bug directory has not been configured"))
    .toFile()
    .listFiles()!!
    .mapNotNull {
        try {
            Json.decodeFromString<ReportedBug>(it.readText())
        } catch (e: SerializationException) {
            println("Skipping $it")
            null
        } catch (e: IllegalArgumentException) {
            println("Skipping $it")
            null
        }
    }
