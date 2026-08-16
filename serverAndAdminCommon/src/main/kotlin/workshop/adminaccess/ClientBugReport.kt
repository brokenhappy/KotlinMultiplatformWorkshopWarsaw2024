package workshop.adminaccess

import kmpworkshop.common.ClientBugReport
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class ServerBugDiagnostics(val values: Map<String, String>, val failures: List<String>)

/** A client report after the server has attached authoritative server context. */
@Serializable
data class StoredClientBugReport(
    val clientReport: ClientBugReport,
    val serverDiagnostics: ServerBugDiagnostics,
    val serverState: ServerState,
    val receivedAt: Instant,
)
