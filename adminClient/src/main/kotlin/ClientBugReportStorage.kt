import kotlinx.serialization.json.Json
import workshop.adminaccess.PendingBug
import workshop.adminaccess.ReportedBug
import workshop.adminaccess.StoredClientBugReport
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories

private val bugJson = Json { prettyPrint = true }

internal fun persistPendingBugLocally(bug: PendingBug) {
    persistPendingBugLocally(bug, configuredBugDirectory())
}

internal fun persistPendingBugLocally(bug: PendingBug, bugDirectory: Path) {
    when (bug) {
        is PendingBug.Client -> persistClientBugReportLocally(bug.report, bugDirectory)
        is PendingBug.Server -> persistServerBugLocally(bug.report, bugDirectory)
    }
}

internal fun persistServerBugLocally(report: ReportedBug, bugDirectory: Path) {
    persistReport(bugDirectory, "Bug", report.id, bugJson.encodeToString(report))
}

internal fun persistClientBugReportLocally(report: StoredClientBugReport, bugDirectory: Path) {
    persistReport(bugDirectory.resolve("client_bug_reports"), "Client bug", report.id, bugJson.encodeToString(report))
}

private fun configuredBugDirectory(): Path = System.getenv("BUG_DIRECTORY")?.let(::javaPath)
    ?: error("BUG_DIRECTORY is not configured for AdminUI")

private fun persistReport(directory: Path, prefix: String, id: String, contents: String) {
    directory.createDirectories()
    val target = directory.resolve("$prefix ${UUID.fromString(id)}.json")
    if (Files.exists(target)) {
        check(Files.readString(target) == contents) { "A different bug report already exists at $target" }
        return
    }
    val temporary = Files.createTempFile(directory, ".bug-report-", ".tmp")
    try {
        Files.writeString(temporary, contents)
        try {
            Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target)
        } catch (_: FileAlreadyExistsException) {
            // A second admin connection may have stored the same report already.
        }
        check(Files.readString(target) == contents) { "A different bug report already exists at $target" }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun javaPath(value: String): Path = Path.of(value)
