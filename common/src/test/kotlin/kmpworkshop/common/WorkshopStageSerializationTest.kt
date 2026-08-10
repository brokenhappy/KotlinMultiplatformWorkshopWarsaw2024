package kmpworkshop.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class WorkshopStageSerializationTest {
    @Test
    fun `serializes enum workshop stages without a polymorphic discriminator`() {
        val json = Json.encodeToString<WorkshopStage>(WorkshopStage.KotlinBasicsPuzzleStage.PalindromeCheckTask)

        assertEquals("\"PalindromeCheckTask\"", json)
        assertEquals(
            WorkshopStage.KotlinBasicsPuzzleStage.PalindromeCheckTask,
            Json.decodeFromString<WorkshopStage>(json),
        )
    }

    @Test
    fun `serializes registration and restores its subtype`() {
        val json = Json.encodeToString<WorkshopStage>(WorkshopStage.Registration)

        assertEquals(WorkshopStage.Registration, Json.decodeFromString<WorkshopStage>(json))
    }

    @Test
    fun `puzzle stage enums have no overlapping names`() {
        val kotlinBasicsNames = WorkshopStage.KotlinBasicsPuzzleStage.entries.map { it.name }.toSet()
        val coroutineNames = WorkshopStage.CoroutinePuzzleStage.entries.map { it.name }.toSet()

        assertEquals(
            emptySet(),
            kotlinBasicsNames intersect coroutineNames,
            "Enum names are the WorkshopStage subtype marker in JSON. Rename stages so the two enums have no names in common.",
        )
    }
}
