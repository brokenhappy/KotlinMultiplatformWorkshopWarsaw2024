package bugreproducer

import kmpworkshop.common.coroutinesToLoom
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import workshop.adminaccess.StoredClientBugReport
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

data class GitResult(
    val output: String,
    val error: String,
    val exitCode: Int,
)

/** Small, argument-array-only Git wrapper. Reported values are never interpolated into a shell command. */
open class GitRunner(private val timeout: Long = 10, private val unit: TimeUnit = TimeUnit.SECONDS) {
    open suspend fun run(directory: Path, vararg arguments: String, input: String? = null): GitResult = coroutineScope {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(directory.toFile())
            .redirectErrorStream(false)
            .start()

        if (input != null) {
            process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(input) }
        } else {
            process.outputStream.close()
        }

        val output = async {
            coroutinesToLoom {
                process.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            }
        }
        val error = async {
            coroutinesToLoom {
                process.errorStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            }
        }
        try {
            if (!coroutinesToLoom { process.waitFor(timeout, unit) }) {
                terminateProcessTree(process)
                GitResult(output.await(), "git command timed out", -1)
            } else {
                GitResult(output.await(), error.await(), process.exitValue())
            }
        } catch (cancellation: CancellationException) {
            terminateProcessTree(process)
            throw cancellation
        } finally {
            if (process.isAlive) terminateProcessTree(process)
        }
    }

    suspend fun require(directory: Path, vararg arguments: String, input: String? = null): String =
        run(directory, *arguments, input = input).let { result ->
            check(result.exitCode == 0) {
                "git ${arguments.joinToString(" ")} failed: ${result.error.trim().ifBlank { result.output.trim() }}"
            }
            result.output
        }

}

internal suspend fun terminateProcessTree(process: Process) {
    withContext(NonCancellable) {
        coroutinesToLoom {
            runCatching { process.toHandle().descendants().forEach { it.destroyForcibly() } }
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(1, TimeUnit.SECONDS) }
        }
    }
}

@Serializable
data class ReproductionSettings(val zoom: Float)

@Serializable
data class ReproductionConfig(
    val report: StoredClientBugReport,
    val settings: ReproductionSettings,
    val worktree: String,
    val apiKey: String,
)

sealed class WorktreePreparationResult {
    data class Ready(val prepared: PreparedWorktree) : WorktreePreparationResult()
    data class Conflicts(
        val worktree: Path,
        val paths: List<String>,
        val warnings: List<String>,
        val clientCommit: String? = null,
        val serverCommit: String? = null,
        val clientChangesCommit: String? = null,
        val serverChangesCommit: String? = null,
    ) : WorktreePreparationResult()

    data class Failed(val message: String) : WorktreePreparationResult()
}

data class PreparedWorktree(
    val directory: Path,
    val warnings: List<String>,
    val clientCommit: String? = null,
    val serverCommit: String? = null,
    val clientChangesCommit: String? = null,
    val serverChangesCommit: String? = null,
)

private class CompositionConflict(val paths: List<String>) : RuntimeException()

/**
 * The worktree lifecycle required by the reproduction operation. Implementations retain a conflicted worktree until
 * [cleanup] is called so a user or test can resolve and revalidate it.
 */
interface BugReproducerWorktreeManager {
    suspend fun prepare(diagnostics: ParsedReproductionDiagnostics): WorktreePreparationResult

    suspend fun continuePreparation(conflicts: WorktreePreparationResult.Conflicts): WorktreePreparationResult

    suspend fun cleanup(worktree: Path)
}

/**
 * Composes historical source without ever checking out a report revision in the user's checkout.
 * The temporary worktree is intentionally retained when composition has conflicts.
 */
class GitBugReproducerWorktreeManager(
    launcherCheckout: Path = Path.of("."),
    private val git: GitRunner = GitRunner(),
    commitHashDump: Path? = null,
) : BugReproducerWorktreeManager {
    private val launcherCheckout: Path = findGitCheckout(launcherCheckout)
    private val commitHashDumpPath: Path? = commitHashDump?.toAbsolutePath()?.normalize()
    private val commitHashDumpText: String? = when {
        commitHashDumpPath != null -> commitHashDumpPath.takeIf { it.exists() }?.readText()
        commitHashesDump.isNotBlank() -> commitHashesDump
        else -> null
    }

    override suspend fun prepare(diagnostics: ParsedReproductionDiagnostics): WorktreePreparationResult {
        val finalDirectory = createTemporaryWorktreeDirectory()
        val helperDirectories = mutableListOf<Path>()
        val temporaryBranches = temporaryBranchNames(finalDirectory)
        var warnings = diagnostics.warnings
        var retainFinalDirectory = false
        var resolvedClientCommit: String? = null
        var resolvedServerCommit: String? = null
        var clientChangesCommit: String? = null
        var serverChangesCommit: String? = null
        try {
            val client = resolveCommit(diagnostics.clientCommit, "client")
            val server = resolveCommit(diagnostics.serverCommit, "server")
            resolvedClientCommit = client.mapping.targetHash
            resolvedServerCommit = server.mapping.targetHash
            warnings += listOfNotNull(client.note, server.note)

            val (first, second) = orderSides(
                ReportSide(
                    CapturedSide.Client,
                    CapturedSide.Client.label,
                    resolvedClientCommit,
                    listOf(diagnostics.clientLocalChanges),
                ),
                ReportSide(
                    CapturedSide.Server,
                    CapturedSide.Server.label,
                    resolvedServerCommit,
                    listOf(diagnostics.serverChanges, diagnostics.serverUntrackedChanges),
                ),
            )

            // Build the later side in a helper worktree first. The helper worktree is removed before rebase so the
            // source branch can be checked out in the final worktree and Git can leave its rebase state there.
            val sourceWorktree = finalDirectory.parent.resolve("source-commit")
            helperDirectories.add(sourceWorktree)
            val secondCapturedCommit = createCapturedChangesBranch(sourceWorktree, temporaryBranches.source, second)
            warnings += secondCapturedCommit.warnings
            when (second.kind) {
                CapturedSide.Client -> clientChangesCommit = secondCapturedCommit.hash
                CapturedSide.Server -> serverChangesCommit = secondCapturedCommit.hash
            }
            removeGitWorktreeOnly(sourceWorktree)

            git.require(
                launcherCheckout,
                "worktree",
                "add",
                "-b",
                temporaryBranches.reproduction,
                finalDirectory.toString(),
                first.publicCommit,
            )
            val firstCapturedCommitResult = applyAndCommitCapturedChanges(finalDirectory, first.capturedChanges)
            warnings += firstCapturedCommitResult.warnings
            when (first.kind) {
                CapturedSide.Client -> clientChangesCommit = firstCapturedCommitResult.hash
                CapturedSide.Server -> serverChangesCommit = firstCapturedCommitResult.hash
            }
            val firstCapturedCommit = git.require(finalDirectory, "rev-parse", "HEAD").trim()

            val rebase = git.run(
                finalDirectory,
                "-c",
                "user.name=Bug Reproducer",
                "-c",
                "user.email=bug-reproducer@localhost",
                "rebase",
                "--onto",
                firstCapturedCommit,
                first.publicCommit,
                temporaryBranches.source,
            )
            if (rebase.exitCode != 0) {
                val paths = unmergedPaths(finalDirectory)
                if (paths.isNotEmpty()) throw CompositionConflict(paths)
                error("Could not rebase the captured client and server states: ${gitFailure(rebase)}")
            }

            clientChangesCommit = findCapturedChangesCommit(finalDirectory, CapturedSide.Client) ?: clientChangesCommit
            serverChangesCommit = findCapturedChangesCommit(finalDirectory, CapturedSide.Server) ?: serverChangesCommit

            copyLauncherScaffolding(finalDirectory)
            return WorktreePreparationResult.Ready(
                PreparedWorktree(
                    directory = finalDirectory,
                    warnings = warnings,
                    clientCommit = resolvedClientCommit,
                    serverCommit = resolvedServerCommit,
                    clientChangesCommit = clientChangesCommit,
                    serverChangesCommit = serverChangesCommit,
                ),
            ).also { retainFinalDirectory = true }
        } catch (conflict: CompositionConflict) {
            // Do not abort or clean the rebase. The final worktree and its index are the input for the conflict UI
            // and for a later `rebase --continue` after the user or Codex resolves and stages the files.
            copyLauncherScaffolding(finalDirectory)
            return WorktreePreparationResult.Conflicts(
                worktree = finalDirectory,
                paths = conflict.paths.distinct().sorted(),
                warnings = warnings,
                clientCommit = resolvedClientCommit,
                serverCommit = resolvedServerCommit,
                clientChangesCommit = clientChangesCommit,
                serverChangesCommit = serverChangesCommit,
            ).also { retainFinalDirectory = true }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return WorktreePreparationResult.Failed(failure.message ?: failure::class.simpleName.orEmpty())
        } finally {
            withContext(NonCancellable) {
                helperDirectories.asReversed().forEach { removeGitWorktreeOnly(it) }
                if (!retainFinalDirectory) {
                    removeGitWorktreeOnly(finalDirectory)
                    deleteTemporaryBranches(temporaryBranches)
                }
                cleanupTemporaryContainer(finalDirectory.parent)
            }
        }
    }

    override suspend fun continuePreparation(
        conflicts: WorktreePreparationResult.Conflicts,
    ): WorktreePreparationResult {
        val worktree = conflicts.worktree
        val paths = unmergedPaths(worktree)
        if (!isRebaseInProgress(worktree)) {
            return if (paths.isEmpty()) {
                val clientChangesCommit = findCapturedChangesCommit(worktree, CapturedSide.Client)
                    ?: conflicts.clientChangesCommit
                val serverChangesCommit = findCapturedChangesCommit(worktree, CapturedSide.Server)
                    ?: conflicts.serverChangesCommit
                WorktreePreparationResult.Ready(
                    PreparedWorktree(
                        directory = worktree,
                        warnings = conflicts.warnings,
                        clientCommit = conflicts.clientCommit,
                        serverCommit = conflicts.serverCommit,
                        clientChangesCommit = clientChangesCommit,
                        serverChangesCommit = serverChangesCommit,
                    ),
                )
            } else {
                conflicts.copy(paths = paths)
            }
        }

        val continuation = git.run(
            worktree,
            "-c",
            "user.name=Bug Reproducer",
            "-c",
            "user.email=bug-reproducer@localhost",
            "-c",
            "core.editor=true",
            "rebase",
            "--continue",
        )
        if (continuation.exitCode == 0) {
            val clientChangesCommit = findCapturedChangesCommit(worktree, CapturedSide.Client)
                ?: conflicts.clientChangesCommit
            val serverChangesCommit = findCapturedChangesCommit(worktree, CapturedSide.Server)
                ?: conflicts.serverChangesCommit
            return WorktreePreparationResult.Ready(
                PreparedWorktree(
                    directory = worktree,
                    warnings = conflicts.warnings,
                    clientCommit = conflicts.clientCommit,
                    serverCommit = conflicts.serverCommit,
                    clientChangesCommit = clientChangesCommit,
                    serverChangesCommit = serverChangesCommit,
                ),
            )
        }

        val remainingPaths = unmergedPaths(worktree)
        return if (remainingPaths.isNotEmpty()) {
            conflicts.copy(paths = remainingPaths)
        } else {
            WorktreePreparationResult.Failed("Could not continue the rebase: ${gitFailure(continuation)}")
        }
    }

    private suspend fun resolveCommit(commit: String, label: String): ResolvedCommit {
        if (git.run(launcherCheckout, "cat-file", "-e", "$commit^{commit}").exitCode == 0) {
            val fullHash = git.require(launcherCheckout, "rev-parse", "$commit^{commit}").trim()
            return ResolvedCommit(CommitMapping(commit, fullHash, "", true), null)
        }
        check(commitHashDumpText != null) {
            val source = commitHashDumpPath?.toString()
                ?: "bugReproducer/src/main/kotlin/bugreproducer/commitHashesDump.kt"
            "The $label report revision is not available in the launcher checkout: $commit, and no commit hash dump was found at $source"
        }
        val checkoutLog = git.require(launcherCheckout, "log", "--all", "--format=%H%x09%s")
        val mapper = CommitHashMapper(parseCommitHashDump(commitHashDumpText), parseGitLogSubjects(checkoutLog))
        val mapping = (if (label == "server") mapper.resolveBestEffort(commit) else mapper.resolve(commit))
            .getOrElse {
                error(
                    "The $label report revision is not available in the launcher checkout: $commit. " +
                        "Commit-message mapping failed: ${it.message}",
                )
            }
        val note = if (mapping.bestEffort) {
            "No exact subject match was found for $label report revision $commit " +
                "(‘${mapping.sourceSubject}’); treating it as a server-repository-only/devops commit and using " +
                "the nearest older mapped commit ${mapping.targetHash} (‘${mapping.subject}’) as a best-effort " +
                "reproduction."
        } else {
            "Mapped $label report revision $commit to ${mapping.targetHash} by matching commit message " +
                "‘${mapping.subject}’."
        }
        return ResolvedCommit(mapping, note)
    }

    private suspend fun orderSides(client: ReportSide, server: ReportSide): Pair<ReportSide, ReportSide> {
        if (client.publicCommit == server.publicCommit) return client to server
        val clientBeforeServer = git.run(
            launcherCheckout,
            "merge-base",
            "--is-ancestor",
            client.publicCommit,
            server.publicCommit,
        ).exitCode == 0
        if (clientBeforeServer) return client to server

        val serverBeforeClient = git.run(
            launcherCheckout,
            "merge-base",
            "--is-ancestor",
            server.publicCommit,
            client.publicCommit,
        ).exitCode == 0
        check(serverBeforeClient) {
            "The resolved client and server revisions do not have a linear history: " +
                "client=${client.publicCommit}, server=${server.publicCommit}."
        }
        return server to client
    }

    private suspend fun createCapturedChangesBranch(
        worktree: Path,
        branch: String,
        side: ReportSide,
    ): CapturedCommit {
        git.require(launcherCheckout, "worktree", "add", "-b", branch, worktree.toString(), side.publicCommit)
        return applyAndCommitCapturedChanges(worktree, side.capturedChanges)
    }

    private suspend fun applyAndCommitCapturedChanges(worktree: Path, changes: CapturedChanges): CapturedCommit {
        val warnings = buildList {
            changes.patches.forEachIndexed { index, patch ->
                applyCapturedPatch(worktree, patch, "${changes.label}-${index + 1}")?.let(::add)
            }
        }
        git.require(worktree, "add", "--all")
        git.require(
            worktree,
            "-c",
            "user.name=Bug Reproducer",
            "-c",
            "user.email=bug-reproducer@localhost",
            "commit",
            "--allow-empty",
            "-m",
            "Captured ${changes.label}",
        )
        return CapturedCommit(
            hash = git.require(worktree, "rev-parse", "HEAD").trim(),
            warnings = warnings,
        )
    }

    private suspend fun findCapturedChangesCommit(worktree: Path, side: CapturedSide): String? {
        val subject = "Captured ${side.label}"
        return git.run(worktree, "log", "--all", "--format=%H%x09%s")
            .takeIf { it.exitCode == 0 }
            ?.output
            ?.lineSequence()
            ?.mapNotNull { line ->
                val separator = line.indexOf('\t')
                if (separator <= 0 || line.substring(separator + 1) != subject) null
                else line.substring(0, separator)
            }
            ?.firstOrNull()
    }

    private suspend fun applyCapturedPatch(worktree: Path, patch: String, label: String): String? {
        if (patch.isBlank()) return null
        // Reports written by older clients may have lost only the final newline. Restoring that delimiter does not
        // alter the patch contents; it lets Git parse the complete captured patch.
        val patchForGit = if (patch.endsWith('\n')) patch else "$patch\n"
        val patchFile = worktree.parent.resolve("$label.patch").also { it.writeText(patchForGit) }
        try {
            val result = applyPatchFile(worktree, patchFile)
            if (result.exitCode == 0) return null

            if (applyPatchFile(worktree, patchFile, threeWay = false).exitCode == 0) {
                return "Applied the captured $label directly because its Git blob IDs are from the source repository."
            }

            val details = result.error.trim().ifBlank { result.output.trim() }
            if (details.contains("corrupt patch", ignoreCase = true)) {
                recoverCompleteFilePatches(worktree, patch, label)?.let { warning -> return warning }
            }
            val paths = unmergedPaths(worktree)
            error(
                "Could not apply $label: ${details.take(1_000)}" +
                    if (paths.isEmpty()) "." else ". Unmerged paths: ${paths.joinToString()}.",
            )
        } finally {
            patchFile.deleteIfExists()
        }
    }

    private suspend fun applyPatchFile(worktree: Path, patchFile: Path, threeWay: Boolean = true): GitResult = if (threeWay) {
        git.run(worktree, "apply", "--3way", "--binary", "--whitespace=nowarn", patchFile.toString())
    } else {
        git.run(worktree, "apply", "--binary", "--whitespace=nowarn", patchFile.toString())
    }

    private suspend fun recoverCompleteFilePatches(worktree: Path, patch: String, label: String): String? {
        val patchStarts = Regex("(?m)^diff --git ").findAll(patch).map { it.range.first }.toList()
        if (patchStarts.size < 2) return null
        val completePatch = patch.substring(patchStarts.first(), patchStarts.last()).trimEnd() + "\n"
        val recoveryFile = worktree.parent.resolve("$label-complete.patch").also { it.writeText(completePatch) }
        return try {
            val result = applyPatchFile(worktree, recoveryFile)
            if (result.exitCode == 0) {
                "The captured $label ended inside its final file patch; applied the complete preceding file patches only. " +
                    "This reproduction may be incomplete."
            } else {
                null
            }
        } finally {
            recoveryFile.deleteIfExists()
        }
    }

    private suspend fun unmergedPaths(worktree: Path): List<String> = buildList {
        val indexPaths = git.run(worktree, "diff", "--name-only", "--diff-filter=U")
        if (indexPaths.exitCode == 0) addAll(indexPaths.output.lineSequence().filter(String::isNotBlank))
        val stagedPaths = git.run(worktree, "ls-files", "-u")
        if (stagedPaths.exitCode == 0) {
            addAll(stagedPaths.output.lineSequence().mapNotNull { it.substringAfter('\t', "").takeIf(String::isNotBlank) })
        }
    }.distinct().sorted()

    private suspend fun isRebaseInProgress(worktree: Path): Boolean {
        val mergeDirectory = git.run(worktree, "rev-parse", "--git-path", "rebase-merge")
            .takeIf { it.exitCode == 0 }
            ?.output
            ?.trim()
            ?.let(Path::of)
        val applyDirectory = git.run(worktree, "rev-parse", "--git-path", "rebase-apply")
            .takeIf { it.exitCode == 0 }
            ?.output
            ?.trim()
            ?.let(Path::of)
        return listOfNotNull(mergeDirectory, applyDirectory).any { it.exists() }
    }

    override suspend fun cleanup(worktree: Path) = withContext(NonCancellable) {
        removeGitWorktreeOnly(worktree)
        deleteTemporaryBranches(temporaryBranchNames(worktree))
        cleanupTemporaryContainer(worktree.parent)
    }

    private suspend fun removeGitWorktreeOnly(directory: Path) {
        if (directory.exists()) {
            runCatching { git.run(launcherCheckout, "worktree", "remove", "--force", directory.toString()) }
            deleteTree(directory)
        }
    }

    private suspend fun deleteTemporaryBranches(branches: TemporaryBranches) {
        listOf(branches.source, branches.reproduction).forEach { branch ->
            runCatching { git.run(launcherCheckout, "branch", "-D", branch) }
        }
    }

    private fun cleanupTemporaryContainer(directory: Path?) {
        if (directory == null || !directory.fileName.toString().startsWith("bug-reproducer-") || !directory.isDirectory()) return
        val isEmpty = Files.list(directory).use { stream -> !stream.findAny().isPresent }
        if (isEmpty) deleteTree(directory)
    }

    private fun copyLauncherScaffolding(worktree: Path) {
        copyDirectoryContents(launcherCheckout.resolve("bugReproducer"), worktree.resolve("bugReproducer"))
        copyFileIfPresent(launcherCheckout.resolve("settings.gradle.kts"), worktree.resolve("settings.gradle.kts"))
        copyFileIfPresent(launcherCheckout.resolve("build.gradle.kts"), worktree.resolve("build.gradle.kts"))
    }

    private data class ResolvedCommit(val mapping: CommitMapping, val note: String?)

    private data class ReportSide(
        val kind: CapturedSide,
        val label: String,
        val publicCommit: String,
        val patches: List<String>,
    ) {
        val capturedChanges: CapturedChanges get() = CapturedChanges(label, publicCommit, patches)
    }

    private data class CapturedChanges(
        val label: String,
        val publicCommit: String,
        val patches: List<String>,
    )

    private data class CapturedCommit(val hash: String, val warnings: List<String>)

    private enum class CapturedSide(val label: String) {
        Client("client local changes"),
        Server("server changes"),
    }

    private data class TemporaryBranches(val source: String, val reproduction: String)

    private fun temporaryBranchNames(worktree: Path): TemporaryBranches {
        val token = worktree.parent.fileName.toString().replace(Regex("[^A-Za-z0-9._-]"), "-")
        return TemporaryBranches(
            source = "bug-reproducer/$token/source",
            reproduction = "bug-reproducer/$token/reproduction",
        )
    }

    private fun createTemporaryWorktreeDirectory(): Path {
        val container = Files.createTempDirectory("bug-reproducer-")
        return container.resolve("checkout")
    }
}

private fun gitFailure(result: GitResult): String =
    result.error.trim().ifBlank { result.output.trim() }.ifBlank { "git exited with code ${result.exitCode}" }

private fun Path.isRegularFile(): Boolean = Files.isRegularFile(this)

private fun copyDirectoryContents(source: Path, destination: Path) {
    if (!source.isDirectory()) return
    if (destination.exists()) deleteTree(destination)
    destination.createDirectories()
    Files.walk(source).use { stream ->
        stream.forEach { path ->
            val relative = source.relativize(path)
            val target = destination.resolve(relative)
            if (path.isDirectory()) target.createDirectories()
            else if (path.isRegularFile()) {
                target.parent.createDirectories()
                Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

private fun copyFileIfPresent(source: Path, destination: Path) {
    if (!source.isRegularFile()) return
    destination.parent.createDirectories()
    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
}

private fun deleteTree(directory: Path?) {
    if (directory == null || !directory.exists()) return
    Files.walk(directory).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
    }
}

private fun loadConfigJson(path: Path): ReproductionConfig = Json { ignoreUnknownKeys = true }.decodeFromString(path.readText())

fun readReproductionConfig(path: Path): ReproductionConfig = loadConfigJson(path)

fun apiKeyFromAppliedClientFiles(worktree: Path): Result<String> = runCatching {
    val candidates = buildList {
        add(worktree.resolve("common/src/main/kotlin/kmpworkshop/common/Secrets.kt"))
        add(worktree.resolve("client/src/main/kotlin/kmpworkshop/client/Secrets.kt"))
        listOf(worktree.resolve("common"), worktree.resolve("client")).forEach { directory ->
            if (directory.isDirectory()) {
                Files.walk(directory).use { stream ->
                    stream.filter { it.isRegularFile() && it.toString().endsWith(".kt") }.forEach { add(it) }
                }
            }
        }
    }.distinct()
    candidates.asSequence()
        .filter { it.isRegularFile() }
        .mapNotNull { path -> runCatching { apiKeyFromAppliedClientSource(path.readText()).getOrNull() }.getOrNull() }
        .firstOrNull()
        ?: error("The applied client source does not contain a usable client API key.")
}

internal fun findGitCheckout(start: Path): Path {
    var candidate: Path? = start.toAbsolutePath().normalize()
    while (candidate != null) {
        if (Files.exists(candidate.resolve(".git"))) return candidate
        candidate = candidate.parent
    }
    return start.toAbsolutePath().normalize()
}
