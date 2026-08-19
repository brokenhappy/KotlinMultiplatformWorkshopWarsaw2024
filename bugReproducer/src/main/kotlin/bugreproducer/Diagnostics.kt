package bugreproducer

import kmpworkshop.client.ClientSettings
import workshop.adminaccess.StoredClientBugReport

private const val MinimumZoom = 0.3f
private const val MaximumZoom = 3.0f

data class ParsedReproductionDiagnostics(
    val clientCommit: String,
    val serverCommit: String,
    val clientLocalChanges: String,
    val serverChanges: String,
    val serverUntrackedChanges: String,
    val clientSettings: ClientSettings,
    val warnings: List<String>,
)

/**
 * Converts the untyped diagnostic maps written by the client and server into the small, checked input needed by
 * the reproducer. A report with missing or malformed provenance is kept visible but cannot be launched.
 */
fun parseReproductionDiagnostics(report: StoredClientBugReport): Result<ParsedReproductionDiagnostics> = runCatching {
    val client = report.clientReport.diagnostics.values
    val server = report.serverDiagnostics.values

    fun required(values: Map<String, String>, key: String): String =
        values[key]?.takeIf { it.isNotBlank() }
            ?: error("Missing diagnostic '$key'. The report does not contain enough provenance to reproduce it.")

    fun commit(values: Map<String, String>, key: String): String {
        val value = required(values, key).trim()
        require(Regex("[0-9a-fA-F]{7,64}").matches(value)) {
            "Diagnostic '$key' is not a valid Git commit: $value"
        }
        return value
    }

    fun diff(values: Map<String, String>, key: String): String = values[key] ?: error(
        "Missing diagnostic '$key'. The report cannot reconstruct the captured changes.",
    )

    val settingsText = required(client, "client.settings")
    val zoomText = Regex("^\\s*zoom\\s*=\\s*(.+?)\\s*$")
        .matchEntire(settingsText)?.groupValues?.get(1)
        ?: error("Diagnostic 'client.settings' has an invalid value: $settingsText")
    val zoom = zoomText.toFloatOrNull()
        ?: error("Diagnostic 'client.settings' has an invalid zoom: $zoomText")
    require(zoom.isFinite() && zoom in MinimumZoom..MaximumZoom) {
        "Diagnostic 'client.settings' has an out-of-range zoom: $zoom"
    }

    val warnings = buildList {
        val untracked = client["client.git.untrackedFiles"].orEmpty().trim()
        if (untracked.isNotEmpty()) {
            add("Client untracked files were reported without their contents and cannot be reconstructed: $untracked")
        }
        if (client["client.git.localChangesTruncated"]?.toBooleanStrictOrNull() == true) {
            add("The captured client local changes were truncated and may not reproduce the report exactly.")
        }
        report.clientReport.diagnostics.failures.forEach { add("Client diagnostic: $it") }
        report.serverDiagnostics.failures.forEach { add("Server diagnostic: $it") }
        if (server["server.changesTruncated"]?.toBooleanStrictOrNull() == true) {
            add("The captured server changes were truncated and may not reproduce the report exactly.")
        }
        if (server["server.untrackedChangesTruncated"]?.toBooleanStrictOrNull() == true) {
            add("The captured server untracked changes were truncated and may not reproduce the report exactly.")
        }
    }

    ParsedReproductionDiagnostics(
        clientCommit = commit(client, "client.git.checkedOutCommit"),
        serverCommit = commit(server, "server.checkedOutCommit"),
        clientLocalChanges = diff(client, "client.git.localChanges"),
        serverChanges = diff(server, "server.changes"),
        serverUntrackedChanges = diff(server, "server.untrackedChanges"),
        clientSettings = ClientSettings(zoom),
        warnings = warnings,
    )
}

private val apiKeyDeclaration = Regex(
    "\\bclientApiKey\\b\\s*:\\s*String\\s*\\?\\s*=\\s*(?:\"([^\"]*)\"|(null))",
)

/** Finds the API key in the applied historical source, including a `null` declaration. */
fun apiKeyFromAppliedClientSource(source: String): Result<String> = runCatching {
    val declaration = apiKeyDeclaration.find(source)
        ?: error("The applied client source does not declare clientApiKey.")
    declaration.groups[1]?.value?.takeIf { it.isNotBlank() }
        ?: error("The applied client source does not contain a usable client API key.")
}
