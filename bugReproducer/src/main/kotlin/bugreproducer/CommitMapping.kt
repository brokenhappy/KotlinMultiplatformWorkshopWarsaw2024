package bugreproducer

import java.nio.file.Path

data class CommitLogEntry(
    val hash: String,
    val subject: String,
)

data class CommitMapping(
    val sourceHash: String,
    val targetHash: String,
    val subject: String,
    val exactHash: Boolean,
    val bestEffort: Boolean = false,
    val sourceSubject: String = subject,
)

data class CommitMappingReport(
    val mappings: List<CommitMapping>,
    val unmapped: List<CommitLogEntry>,
)

/** Parses the `hash subject` format produced by the supplied other-repository dump. */
fun parseCommitHashDump(text: String): List<CommitLogEntry> = text.lineSequence()
    .mapNotNull { line ->
        Regex("^([0-9a-fA-F]{40})\\s+(.+?)\\s*$").matchEntire(line.trim())?.let {
            CommitLogEntry(it.groupValues[1], it.groupValues[2])
        }
    }
    .toList()

/** Parses `git log --format=%H%x09%s` output from this checkout. */
fun parseGitLogSubjects(text: String): List<CommitLogEntry> = text.lineSequence()
    .mapNotNull { line ->
        val separator = line.indexOf('\t')
        if (separator <= 0 || separator == line.lastIndex) return@mapNotNull null
        val hash = line.substring(0, separator)
        val subject = line.substring(separator + 1)
        if (!Regex("[0-9a-fA-F]{40}").matches(hash) || subject.isBlank()) null
        else CommitLogEntry(hash, subject)
    }
    .toList()

/**
 * Matches unrelated repositories by exact commit subject. Ambiguous subjects are deliberately rejected rather than
 * silently selecting an arbitrary commit.
 */
class CommitHashMapper(
    dumpEntries: List<CommitLogEntry>,
    checkoutEntries: List<CommitLogEntry>,
) {
    private val orderedDumpEntries = dumpEntries
    private val dumpByHash = dumpEntries.associateBy { it.hash }
    private val checkoutHashes = checkoutEntries.mapTo(linkedSetOf()) { it.hash }
    private val checkoutBySubject = checkoutEntries.groupBy { it.subject }

    fun resolve(sourceHash: String): Result<CommitMapping> = runCatching {
        if (sourceHash in checkoutHashes) {
            return@runCatching CommitMapping(sourceHash, sourceHash, dumpByHash[sourceHash]?.subject.orEmpty(), true)
        }
        val source = dumpByHash[sourceHash]
            ?: error("The commit hash dump has no subject for $sourceHash.")
        val candidates = checkoutBySubject[source.subject].orEmpty()
        when (candidates.size) {
            0 -> error("No checkout commit matches the subject '${source.subject}'.")
            1 -> CommitMapping(sourceHash, candidates.single().hash, source.subject, false)
            else -> error(
                "The subject '${source.subject}' matches multiple checkout commits: " +
                    candidates.joinToString { it.hash },
            )
        }
    }

    /**
     * Resolves an exact subject first. If the subject is absent from this checkout, use the nearest older dump entry
     * that does have an unambiguous subject match. This is intentionally marked best-effort because the dump contains
     * subjects but not parent relationships; it is useful for commits such as Docker-only changes between shared
     * application commits, but the launcher always tells the user what happened.
     */
    fun resolveBestEffort(sourceHash: String): Result<CommitMapping> = runCatching {
        val exact = resolve(sourceHash)
        if (exact.isSuccess) return@runCatching exact.getOrThrow()
        val sourceIndex = orderedDumpEntries.indexOfFirst { it.hash == sourceHash }
        val source = orderedDumpEntries.getOrNull(sourceIndex)
            ?: error(exact.exceptionOrNull()?.message ?: "The commit hash dump has no subject for $sourceHash.")
        if (checkoutBySubject[source.subject].orEmpty().isNotEmpty()) {
            error(exact.exceptionOrNull()?.message ?: "The commit subject is ambiguous.")
        }
        val olderMatch = orderedDumpEntries
            .drop(sourceIndex + 1)
            .asSequence()
            .mapNotNull { entry -> resolve(entry.hash).getOrNull() }
            .firstOrNull()
            ?: error(exact.exceptionOrNull()?.message ?: "No older commit can be mapped by subject.")
        olderMatch.copy(
            sourceHash = sourceHash,
            bestEffort = true,
            sourceSubject = source.subject,
        )
    }

    /** Returns every unambiguous subject match, which is useful for inspecting the quality of a dump. */
    fun mapAll(): CommitMappingReport {
        val mappings = mutableListOf<CommitMapping>()
        val unmapped = mutableListOf<CommitLogEntry>()
        dumpByHash.values.forEach { source ->
            resolve(source.hash).onSuccess(mappings::add).onFailure { unmapped += source }
        }
        return CommitMappingReport(mappings.distinctBy { it.sourceHash }, unmapped.distinctBy { it.hash })
    }

    companion object {
        fun fromDumpAndCheckout(dump: Path, checkoutLog: String): CommitHashMapper =
            CommitHashMapper(parseCommitHashDump(dump.toFile().readText()), parseGitLogSubjects(checkoutLog))
    }
}
