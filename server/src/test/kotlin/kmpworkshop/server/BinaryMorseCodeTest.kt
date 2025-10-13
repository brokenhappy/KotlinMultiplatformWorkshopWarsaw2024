package kmpworkshop.server

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import workshop.adminaccess.binaryMoreCodeIdentifiers
import kotlin.random.Random

class BinaryMorseCodeTest {
    @Test
    fun `generates correct ids`() {
        repeat(1_000) {
            val seed = Random.nextLong()
            val ids = with(Random(seed)) { binaryMoreCodeIdentifiers(5) }
            assert(ids.size == 5) { "Must yield 5 ids, got $ids, seed = $seed" }
            assert(ids.toSet().toList() == ids) { "Ids must be unique, got $ids, seed = $seed" }
            ids.forEach { id ->
                assert(id.matches(Regex("[.-]{3}"))) { "Invalid id: $id in $ids, seed = $seed" }
            }
        }
    }
    @Test
    fun `generates correct length Ids`() {
        with(Random) {
            binaryMoreCodeIdentifiers(2).forEach { assertEquals(1, it.length) }
            binaryMoreCodeIdentifiers(3).forEach { assertEquals(2, it.length) }
            binaryMoreCodeIdentifiers(4).forEach { assertEquals(2, it.length) }
            binaryMoreCodeIdentifiers(7).forEach { assertEquals(3, it.length) }
            binaryMoreCodeIdentifiers(8).forEach { assertEquals(3, it.length) }
            binaryMoreCodeIdentifiers(9).forEach { assertEquals(4, it.length) }
            binaryMoreCodeIdentifiers(15).forEach { assertEquals(4, it.length) }
            binaryMoreCodeIdentifiers(16).forEach { assertEquals(4, it.length) }
            binaryMoreCodeIdentifiers(17).forEach { assertEquals(5, it.length) }
        }
    }

    @Test fun `count is zero`() {
        with(Random) {
            assert(binaryMoreCodeIdentifiers(0).isEmpty())
        }
    }

    @Test fun `count is one`() {
        with(Random) {
            assert(binaryMoreCodeIdentifiers(0).isEmpty())
            assertEquals(1, binaryMoreCodeIdentifiers(1).single().length)
        }
    }
}