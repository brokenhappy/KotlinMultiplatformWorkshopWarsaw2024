package bugreproducer

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodexTest {
    @Test
    fun `uses ChatGPT Desktop Codex when it is available`() = withTemporaryWorktree { worktree ->
        val bundledCodex = worktree.resolve("codex").createFile()
        bundledCodex.toFile().setExecutable(true)
        val started = mutableListOf<Pair<List<String>, Path>>()

        openCodexSession(worktree, bundledCodex) { command, directory -> started += command to directory }

        assertEquals(
            listOf(bundledCodex.toString(), "app", worktree.toAbsolutePath().toString()),
            started.single().first,
        )
        assertEquals(worktree, started.single().second)
    }

    @Test
    fun `falls back to jbcentral when the Desktop command is unavailable`() = withTemporaryWorktree { worktree ->
        val started = mutableListOf<Pair<List<String>, Path>>()

        openCodexSession(worktree, Path.of("does-not-exist")) { command, directory -> started += command to directory }

        assertEquals(listOf("jbcentral", "run", "codex"), started.single().first)
        assertEquals(worktree, started.single().second)
    }

    @Test
    fun `reports both launch failures`() = withTemporaryWorktree { worktree ->
        val bundledCodex = worktree.resolve("codex").createFile()
        bundledCodex.toFile().setExecutable(true)

        val failure = assertFailsWith<IllegalStateException> {
            openCodexSession(worktree, bundledCodex) { command, _ ->
                throw IOException("failed ${command.joinToString(" ")}")
            }
        }

        assertTrue(failure.message?.contains("jbcentral run codex") == true)
    }

    private fun withTemporaryWorktree(block: (Path) -> Unit) {
        val worktree = Files.createTempDirectory("codex-worktree")
        try {
            block(worktree)
        } finally {
            worktree.toFile().deleteRecursively()
        }
    }
}
