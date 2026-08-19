package bugreproducer

import kotlinx.serialization.json.Json
import workshop.adminaccess.StoredClientBugReport
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/** A report together with the file it was loaded from, useful when diagnosing malformed input. */
data class LoadedBugReport(
    val path: Path,
    val report: StoredClientBugReport,
)

data class MalformedBugReport(
    val path: Path,
    val reason: String,
)

data class BugReportLoadResult(
    val reports: List<LoadedBugReport>,
    val malformed: List<MalformedBugReport>,
)

private val reportJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Loads only the persisted client reports. The old server-side `Bug*.json` format deliberately is not included.
 * One bad file must not hide the other reports, so malformed files are returned as diagnostics.
 */
fun loadClientBugReports(bugDirectory: Path): BugReportLoadResult {
    val reportDirectory = bugDirectory.resolve("client_bug_reports")
    if (!reportDirectory.isDirectory()) return BugReportLoadResult(emptyList(), emptyList())

    val reports = mutableListOf<LoadedBugReport>()
    val malformed = mutableListOf<MalformedBugReport>()
    Files.list(reportDirectory).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.name.endsWith(".json") }
            .sorted()
            .forEach { path ->
                runCatching { reportJson.decodeFromString<StoredClientBugReport>(path.readText()) }
                    .onSuccess { reports += LoadedBugReport(path, it) }
                    .onFailure {
                        malformed += MalformedBugReport(
                            path,
                            it.message ?: it::class.simpleName ?: "invalid JSON",
                        )
                    }
            }
    }

    return BugReportLoadResult(
        reports = reports.sortedWith(
            compareByDescending<LoadedBugReport> { it.report.receivedAt }
                .thenByDescending { it.path.name },
        ),
        malformed = malformed,
    )
}

fun bugDirectoryFromEnvironment(environment: Map<String, String> = System.getenv()): Path =
    environment["BUG_DIRECTORY"]?.takeIf { it.isNotBlank() }?.let(Path::of)
        ?: error("BUG_DIRECTORY is not configured. Set it to the directory containing client_bug_reports.")

data class DecodedBugImage(
    val bytes: ByteArray,
    val image: BufferedImage,
)

fun decodeBugImage(attachment: kmpworkshop.common.BugImageAttachment): Result<DecodedBugImage> = runCatching {
    val bytes = Base64.getDecoder().decode(attachment.dataBase64)
    val image = ImageIO.read(ByteArrayInputStream(bytes))
        ?: error("${attachment.fileName} is not a supported image")
    DecodedBugImage(bytes, image)
}
