import kotlinx.serialization.json.Json
import workshop.adminaccess.StoredClientBugReport
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

internal fun persistClientBugReportLocally(report: StoredClientBugReport) {
    val bugDirectory = System.getenv("BUG_DIRECTORY")?.let(::javaPath)
        ?: error("BUG_DIRECTORY is not configured for AdminUI")
    persistClientBugReportLocally(report, bugDirectory)
}

internal fun persistClientBugReportLocally(report: StoredClientBugReport, bugDirectory: Path) {
    val reportDirectory = bugDirectory.resolve("client_bug_reports")
    reportDirectory.createDirectories()

    val stem = "Client bug ${report.receivedAt}".replace(Regex("[^A-Za-z0-9._-]"), "_")
    val target = generateSequence(0) { it + 1 }
        .map { index -> reportDirectory.resolve("$stem${if (index == 0) "" else "($index)"}.json") }
        .first { !Files.exists(it) }
    val temporary = reportDirectory.resolve(".${target.fileName}.tmp")
    try {
        temporary.writeText(Json { prettyPrint = true }.encodeToString(report))
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun javaPath(value: String): Path = Path.of(value)
