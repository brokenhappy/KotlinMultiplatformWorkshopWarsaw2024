package bugreproducer

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorktreeTest {
    @Test
    fun `composes ordered client and server revisions and cleans only the temporary worktree`() = runTest {
        GitFixture.create().use { repository ->
            val base = repository.commit("base")
            repository.write("client/src/client.txt", "base client")
            repository.write("common/src/shared.txt", "client version\n")
            val client = repository.commit("client")
            repository.write("server/src/server.txt", "server version")
            val server = repository.commit("server")
            val reportedServerHash = "f".repeat(40)
            val dump = repository.directory.resolve("commit dump.txt").also {
                it.writeText("$reportedServerHash Docker-only change\n${"e".repeat(40)} server\n")
            }

            val manager = GitBugReproducerWorktreeManager(repository.directory, commitHashDump = dump)
            val result = manager.prepare(diagnostics(client, reportedServerHash))
            val ready = assertIs<WorktreePreparationResult.Ready>(result)
            val worktree = ready.prepared.directory

            assertEquals("client version\n", worktree.resolve("common/src/shared.txt").readText())
            assertEquals("base client", worktree.resolve("client/src/client.txt").readText())
            assertEquals("server version", worktree.resolve("server/src/server.txt").readText())
            assertTrue(ready.prepared.warnings.single().contains("server-repository-only/devops commit"))
            assertTrue(ready.prepared.warnings.single().contains("best-effort reproduction"))
            assertTrue(worktree.logSubjects().containsAll(listOf("Captured client local changes", "Captured server changes")))
            assertTrue(ready.prepared.clientChangesCommit != null)
            assertTrue(ready.prepared.serverChangesCommit != null)

            manager.cleanup(worktree)
            assertTrue(Files.exists(repository.directory))
            assertEquals(server, repository.head())
        }
    }

    @Test
    fun `does not use the server-only fallback for an unavailable client revision`() = runTest {
        GitFixture.create().use { repository ->
            val base = repository.commit("base")
            repository.write("client/src/client.txt", "client version")
            val client = repository.commit("client")
            repository.write("server/src/server.txt", "server version")
            val server = repository.commit("server")
            val reportedClientHash = "f".repeat(40)
            val dump = repository.directory.resolve("commit dump.txt").also {
                it.writeText(
                    "$reportedClientHash Docker-only change\n" +
                        "${"e".repeat(40)} client\n",
                )
            }

            val manager = GitBugReproducerWorktreeManager(repository.directory, commitHashDump = dump)
            val result = assertIs<WorktreePreparationResult.Failed>(
                manager.prepare(
                    diagnostics(client, server).copy(
                        clientCommit = reportedClientHash,
                    )
                )
            )

            assertTrue(result.message.contains("client report revision"))
            assertTrue(result.message.contains("No checkout commit matches the subject 'Docker-only change'"))
        }
    }

    @Test
    fun `uses the embedded Kotlin dump when no dump path is supplied`() = runTest {
        GitFixture.create().use { repository ->
            val base = repository.commit("base")
            repository.write("client/src/client.txt", "client version")
            val client = repository.commit("client")
            repository.write("server/src/server.txt", "server version")
            val server = repository.commit("Implement Channel learning coroutine puzzles")

            val manager = GitBugReproducerWorktreeManager(repository.directory)
            val result = assertIs<WorktreePreparationResult.Ready>(
                manager.prepare(diagnostics(client, "667e1ac5a8cf0b0cb49433d8b9f1f68179d9405b")),
            )

            assertTrue(result.prepared.warnings.single().contains("server-repository-only/devops commit"))
            assertTrue(result.prepared.warnings.single().contains(server))
            manager.cleanup(result.prepared.directory)
        }
    }

    @Test
    fun `falls back to direct patch application when a three-way blob is unavailable`() = runTest {
        GitFixture.create().use { repository ->
            val base = repository.commit("base")
            repository.write("client/src/client.txt", "client version")
            val client = repository.commit("client")
            repository.write("client/src/client.txt", "client local change")
            val capturedDiff = repository.diff().replaceFirst(
                Regex("(?m)^index ([0-9a-f]+)\\.([0-9a-f]+)"),
                "index ${'$'}1.${"f".repeat(40)}",
            )
            repository.discardChanges()
            repository.write("server/src/server.txt", "server version")
            val server = repository.commit("server")

            val manager = GitBugReproducerWorktreeManager(
                repository.directory,
                git = RejectingThreeWayGitRunner(),
            )
            val result = assertIs<WorktreePreparationResult.Ready>(
                manager.prepare(diagnostics(client, server).copy(clientLocalChanges = capturedDiff)),
            )

            assertEquals("client local change", result.prepared.directory.resolve("client/src/client.txt").readText())
            assertTrue(
                result.prepared.warnings.any { it.contains("directly because") },
                result.prepared.warnings.toString(),
            )
            manager.cleanup(result.prepared.directory)
        }
    }

    @Test
    fun `keeps client local changes in shared modules after composition`() = runTest {
        GitFixture.create().use { repository ->
            val base = repository.commit("base")
            repository.write("client/src/client.txt", "client version")
            val client = repository.commit("client")
            repository.write("common/src/shared.txt", "client local\n")
            val capturedDiff = repository.diff().removeSuffix("\n")
            repository.discardChanges()
            repository.write("server/src/server.txt", "server version")
            val server = repository.commit("server")

            val manager = GitBugReproducerWorktreeManager(repository.directory)
            val result = assertIs<WorktreePreparationResult.Ready>(
                manager.prepare(diagnostics(client, server).copy(clientLocalChanges = capturedDiff)),
            )

            assertEquals("client local\n", result.prepared.directory.resolve("common/src/shared.txt").readText())
            manager.cleanup(result.prepared.directory)
        }
    }

    @Test
    fun `recovers complete client file patches before a corrupt final patch`() = runTest {
        GitFixture.create().use { repository ->
            val base = repository.commit("base")
            repository.checkout(base)
            repository.write("client/src/first.txt", "first base")
            repository.write("client/src/second.txt", "second base")
            val client = repository.commit("client")
            repository.write("client/src/first.txt", "first changed")
            repository.write("client/src/second.txt", "second changed")
            val capturedDiff = repository.diff()
            val secondFileStart = capturedDiff.lastIndexOf("\ndiff --git ")
            check(secondFileStart > 0)
            val secondContentStart = capturedDiff.lastIndexOf("\n+second changed")
            check(secondContentStart > secondFileStart)
            val corruptDiff = capturedDiff.substring(0, secondContentStart + 1)
            repository.discardChanges()
            repository.write("server/src/server.txt", "server version")
            val server = repository.commit("server")

            val manager = GitBugReproducerWorktreeManager(repository.directory)
            val result = assertIs<WorktreePreparationResult.Ready>(
                manager.prepare(diagnostics(client, server).copy(clientLocalChanges = corruptDiff)),
            )

            assertEquals("first changed", result.prepared.directory.resolve("client/src/first.txt").readText())
            assertEquals("second base", result.prepared.directory.resolve("client/src/second.txt").readText())
            assertTrue(result.prepared.warnings.any { it.contains("complete preceding file patches only") })
            manager.cleanup(result.prepared.directory)
        }
    }

    @Test
    fun `retains shared merge conflicts as unmerged git state`() = runTest {
        val repository = GitFixture.create()
        try {
            val base = repository.commit("base")
            repository.write("common/src/shared.txt", "client version\n")
            val client = repository.commit("client")
            repository.write("common/src/shared.txt", "client local version\n")
            val clientLocalChanges = repository.diff()
            repository.discardChanges()
            repository.write("common/src/shared.txt", "server version\n")
            val server = repository.commit("server")

            val manager = GitBugReproducerWorktreeManager(repository.directory)
            val result = assertIs<WorktreePreparationResult.Conflicts>(
                manager.prepare(diagnostics(client, server).copy(clientLocalChanges = clientLocalChanges)),
            )

            assertTrue(result.paths.any { it.endsWith("common/src/shared.txt") })
            assertTrue(result.clientChangesCommit != null)
            assertTrue(result.serverChangesCommit != null)
            assertTrue(result.worktree.resolve("common/src/shared.txt").readText().contains("<<<<<<<"))
            assertTrue(Files.isRegularFile(result.worktree.resolve(".git")))
            assertTrue(result.worktree.resolve(".git").readText().contains("gitdir:"))
            val rebaseMerge = Path.of(runGit(result.worktree, "rev-parse", "--git-path", "rebase-merge").trim())
            val rebaseApply = Path.of(runGit(result.worktree, "rev-parse", "--git-path", "rebase-apply").trim())
            assertTrue(Files.isDirectory(rebaseMerge) || Files.isDirectory(rebaseApply))

            val stillConflicted = assertIs<WorktreePreparationResult.Conflicts>(manager.continuePreparation(result))
            assertEquals(result.paths, stillConflicted.paths)
            result.worktree.resolve("common/src/shared.txt").writeText("resolved\n")
            runGit(result.worktree, "add", "common/src/shared.txt")
            val ready = assertIs<WorktreePreparationResult.Ready>(manager.continuePreparation(stillConflicted))
            assertEquals("resolved\n", ready.prepared.directory.resolve("common/src/shared.txt").readText())
            assertTrue(ready.prepared.directory.logSubjects().contains("Captured client local changes"))
            manager.cleanup(result.worktree)
        } finally {
            repository.close()
        }
    }

    @Test
    fun `cancellation removes a partially prepared temporary worktree`() = runTest {
        GitFixture.create().use { repository ->
            val base = repository.commit("base")
            repository.write("client/src/client.txt", "client version")
            val client = repository.commit("client")
            repository.write("server/src/server.txt", "server version")
            val server = repository.commit("server")
            val temporaryDirectoriesBefore = temporaryWorktreeDirectories()
            val manager = GitBugReproducerWorktreeManager(
                repository.directory,
                git = CancelAfterFinalWorktreeGitRunner(),
            )

            val preparation = launch { manager.prepare(diagnostics(client, server)) }
            preparation.cancelAndJoin()

            assertEquals(temporaryDirectoriesBefore, temporaryWorktreeDirectories())
        }
    }

    private fun diagnostics(client: String, server: String) = ParsedReproductionDiagnostics(
        clientCommit = client,
        serverCommit = server,
        clientLocalChanges = "",
        serverChanges = "",
        serverUntrackedChanges = "",
        clientSettings = kmpworkshop.client.ClientSettings(),
        warnings = emptyList(),
    )

    private fun temporaryWorktreeDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { stream ->
        stream.iterator().asSequence()
            .filter { it.fileName.toString().startsWith("bug-reproducer-") }
            .toSet()
    }
}

private fun Path.logSubjects(): List<String> = runGit(this, "log", "--format=%s").lineSequence().toList()

private fun runGit(directory: Path, vararg arguments: String): String {
    val process = ProcessBuilder(listOf("git") + arguments).directory(directory.toFile()).redirectErrorStream(true).start()
    val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
    check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
    return output
}

private class RejectingThreeWayGitRunner : GitRunner() {
    override suspend fun run(directory: Path, vararg arguments: String, input: String?): GitResult =
        if ("--3way" in arguments) {
            GitResult("", "fatal: unable to read blob object from source repository", 1)
        } else {
            super.run(directory, *arguments, input = input)
        }
}

private class CancelAfterFinalWorktreeGitRunner : GitRunner() {
    private var worktreeAdds = 0

    override suspend fun run(directory: Path, vararg arguments: String, input: String?): GitResult {
        if (arguments.firstOrNull() == "worktree" && arguments.getOrNull(1) == "add") {
            val result = super.run(directory, *arguments, input = input)
            worktreeAdds++
            if (worktreeAdds == 1) awaitCancellation()
            return result
        }
        return super.run(directory, *arguments, input = input)
    }
}

private class GitFixture private constructor(val directory: Path) : AutoCloseable {
    fun write(relative: String, content: String) {
        directory.resolve(relative).also { it.parent.createDirectories(); it.writeText(content) }
    }

    fun commit(message: String): String {
        run("add", ".")
        run("commit", "-m", message)
        return run("rev-parse", "HEAD").trim()
    }

    fun checkout(commit: String) {
        run("checkout", "--detach", commit)
    }

    fun read(relative: String): String = Files.readString(directory.resolve(relative))

    fun head(): String = run("rev-parse", "HEAD").trim()

    fun logSubjects(): List<String> = run("log", "--format=%s").lineSequence().toList()

    fun diff(): String = run("diff", "--no-ext-diff", "--binary", "HEAD")

    fun discardChanges() {
        run("restore", "--worktree", "--staged", ".")
    }

    override fun close() {
        directory.toFile().deleteRecursively()
    }

    private fun run(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args).directory(directory.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output
    }

    companion object {
        fun create(): GitFixture {
            val fixture = GitFixture(Files.createTempDirectory("bug-reproducer-git"))
            fixture.run("init", "-q")
            fixture.run("config", "user.email", "bug-reproducer@example.test")
            fixture.run("config", "user.name", "Bug Reproducer Tests")
            fixture.write("common/src/shared.txt", "base\n")
            fixture.write("client/src/client.txt", "base client")
            fixture.write("server/src/server.txt", "base server")
            fixture.write("settings.gradle.kts", "rootProject.name = \"fixture\"")
            fixture.write("bugReproducer/src/main.txt", "launcher module")
            return fixture
        }
    }
}
