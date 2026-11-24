package workshop.adminaccess

import kotlinx.serialization.Serializable

/** A bug report waiting for the admin panel to save it locally. */
@Serializable
sealed class PendingBug {
    @Serializable
    data class Client(val report: StoredClientBugReport) : PendingBug()

    @Serializable
    data class Server(val report: ReportedBug) : PendingBug()
}

/** The server-assigned ID used to acknowledge this bug after saving it. */
val PendingBug.id: String
    get() = when (this) {
        is PendingBug.Client -> report.id
        is PendingBug.Server -> report.id
    }
