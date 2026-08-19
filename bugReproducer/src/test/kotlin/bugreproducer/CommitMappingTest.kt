package bugreproducer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommitMappingTest {
    @Test
    fun `maps unrelated repository hashes by unique commit subject`() {
        val sourceHash = "a".repeat(40)
        val targetHash = "b".repeat(40)
        val mapper = CommitHashMapper(
            dumpEntries = listOf(CommitLogEntry(sourceHash, "Fix server")),
            checkoutEntries = listOf(CommitLogEntry(targetHash, "Fix server")),
        )

        val mapping = mapper.resolve(sourceHash).getOrThrow()

        assertEquals(targetHash, mapping.targetHash)
        assertEquals("Fix server", mapping.subject)
        assertTrue(!mapping.exactHash)
    }

    @Test
    fun `rejects ambiguous or missing subjects instead of guessing`() {
        val sourceHash = "a".repeat(40)
        val mapper = CommitHashMapper(
            dumpEntries = listOf(CommitLogEntry(sourceHash, "Repeated")),
            checkoutEntries = listOf(
                CommitLogEntry("b".repeat(40), "Repeated"),
                CommitLogEntry("c".repeat(40), "Repeated"),
            ),
        )

        assertTrue(mapper.resolve(sourceHash).isFailure)
        assertTrue(mapper.resolve("d".repeat(40)).isFailure)
    }

    @Test
    fun `can use the nearest older exact match as an explicit best effort`() {
        val sourceHash = "a".repeat(40)
        val olderSourceHash = "b".repeat(40)
        val targetHash = "c".repeat(40)
        val mapper = CommitHashMapper(
            dumpEntries = listOf(
                CommitLogEntry(sourceHash, "Docker-only change"),
                CommitLogEntry(olderSourceHash, "Shared application change"),
            ),
            checkoutEntries = listOf(CommitLogEntry(targetHash, "Shared application change")),
        )

        val mapping = mapper.resolveBestEffort(sourceHash).getOrThrow()

        assertEquals(targetHash, mapping.targetHash)
        assertTrue(mapping.bestEffort)
        assertEquals("Docker-only change", mapping.sourceSubject)
    }

    @Test
    fun `parses the provided dump and git log formats`() {
        val dump = parseCommitHashDump("${"a".repeat(40)} Message one\ninvalid\n")
        val log = parseGitLogSubjects("${"b".repeat(40)}\tMessage two\n")

        assertEquals(listOf(CommitLogEntry("a".repeat(40), "Message one")), dump)
        assertEquals(listOf(CommitLogEntry("b".repeat(40), "Message two")), log)
    }

    @Test
    fun `includes the Kotlin commit hash dump used by the launcher`() {
        val entries = parseCommitHashDump(commitHashesDump)

        assertEquals("667e1ac5a8cf0b0cb49433d8b9f1f68179d9405b", entries.first().hash)
        assertEquals(
            "Fix Docker build: copy all Gradle modules, not just server's deps",
            entries.first().subject,
        )
    }
}
