package kmpworkshop.client

import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.ktor.client.*
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.*
import kmpworkshop.common.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.rpc.krpc.ktor.client.KtorRpcClient
import kotlinx.rpc.krpc.ktor.client.Krpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import kotlin.time.Duration.Companion.seconds

// This is a hacky solution just so I can keep things simple for the scope of the workshop.
// Please don't use global state like this yourself.
// It's too big of a topic to explain why in these comments.
// Ask me if you're curious why this is not recommended in production environments!
private var _workshopService: WorkshopApiService? = null
val workshopService: WorkshopApiService get() = _workshopService ?: createWorkshopService().also { _workshopService = it }

internal val workshopRpcPingIntervalMillis = 20.seconds.inWholeMilliseconds

/** Keeps the long-lived workshop RPC WebSocket from being discarded by an idle network intermediary. */
internal fun HttpClientConfig<*>.installWorkshopRpc() {
    install(WebSockets) {
        pingIntervalMillis = workshopRpcPingIntervalMillis
    }
    install(Krpc) {
        connector { }
    }
}

/**
 * Connects to the workshop server and returns a [WorkshopApiService] over a real kotlinx-rpc transport, owning the
 * lifetime of the underlying ktor client for the rest of the process (as befits the app's singleton service).
 */
fun createWorkshopService(): WorkshopApiService = runBlocking {
    HttpClient {
        installWorkshopRpc()
    }.connectWorkshopService()
}

/**
 * Wires this ktor client up to the workshop RPC service. The connection parameters default to the production
 * server, but are injectable so that end-to-end tests can point the *same* client wiring at a locally hosted
 * server (e.g. `URLProtocol.WS`, `localhost`, an ephemeral port) - and, by supplying their own [HttpClient], own
 * its lifecycle so it can be closed between runs instead of re-implementing the client setup.
 */
fun HttpClient.connectWorkshopService(
    protocol: URLProtocol = defaultWorkshopProtocol(serverUrl),
    host: String = serverUrl,
    port: Int = serverWebsocketPort,
): WorkshopApiService {
    val client: KtorRpcClient = rpc {
        url {
            this.protocol = protocol
            this.host = host
            this.port = port
            encodedPath = "rpc"
        }

        rpcConfig {
            serialization {
                json()
            }
        }
    }

    return client.withService<WorkshopApiService>()
}

internal fun defaultWorkshopProtocol(host: String): URLProtocol =
    if (host == "localhost" || host == "127.0.0.1" || host == "::1") URLProtocol.WS else URLProtocol.WSS

@Composable
fun ClientEntryPoint(
    clientMetadata: ClientMetadata,
    clientSettings: Flow<ClientSettings>,
    onClientSettingsChange: (ClientSettings) -> Unit,
    openReportBug: Boolean,
    onReportBugClosed: () -> Unit,
) {
    val key = clientApiKey
    if (key == null) {
        Text("Finish registration first, then restart WorkshopClient.")
    } else {
        ClientEntryPoint(
            server = remember { context(clientMetadata) { workshopService.asServer(ApiKey(key)) } },
            submitBugReport = { report -> workshopService.submitClientBugReport(ApiKey(key), report) },
            clientMetadata = clientMetadata,
            clientSettings = clientSettings,
            onClientSettingsChange = onClientSettingsChange,
            openReportBug = openReportBug,
            onReportBugClosed = onReportBugClosed,
        )
    }
}

@Composable
fun ClientEntryPoint(
    server: WorkshopServer,
    submitBugReport: suspend (ClientBugReport) -> ClientBugReportSubmissionResult,
    clientMetadata: ClientMetadata,
    clientSettings: Flow<ClientSettings>,
    onClientSettingsChange: (ClientSettings) -> Unit,
    openReportBug: Boolean,
    onReportBugClosed: () -> Unit,
) {
    WorkshopClient(
        server,
        clientMetadata = clientMetadata,
        clientSettings = clientSettings,
        onClientSettingsChange = onClientSettingsChange,
        submitBugReport = submitBugReport,
        openReportBug = openReportBug,
        onReportBugClosed = onReportBugClosed,
    )
}
