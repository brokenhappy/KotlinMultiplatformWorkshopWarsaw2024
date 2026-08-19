package bugreproducer

import kmpworkshop.client.defaultClientMetadata
import kmpworkshop.client.asServer
import kmpworkshop.common.ApiKey
import kmpworkshop.common.ClientBugReport
import kmpworkshop.common.ClientBugReportSubmissionResult
import kmpworkshop.common.Resource
import kmpworkshop.common.WorkshopApiService
import kmpworkshop.common.WorkshopServer
import kmpworkshop.common.resource
import kmpworkshop.server.mainEventLoopWritingTo
import kmpworkshop.server.workshopService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import workshop.adminaccess.ServerState
import workshop.adminaccess.ScheduledWorkshopEvent

/**
 * Runs the same server state transition loop as the production server, but without opening a network port. The
 * historical client UI talks to this service directly, which makes reproduction deterministic and keeps cancellation
 * under the reproducer window's control.
 */
interface EmbeddedReproducerRuntime {
    val serverState: kotlinx.coroutines.flow.StateFlow<ServerState>
    val client: WorkshopServer

    suspend fun submit(report: ClientBugReport): ClientBugReportSubmissionResult
}

private class EmbeddedReproducerRuntimeImpl(
    initialState: ServerState,
    private val apiKey: String,
    private val eventBus: Channel<ScheduledWorkshopEvent>,
) : EmbeddedReproducerRuntime {
    override val serverState = MutableStateFlow(initialState)
    val service: WorkshopApiService = workshopService(
        serverState = serverState,
        onEvent = { eventBus.trySend(it) },
    )

    override val client: WorkshopServer = context(defaultClientMetadata) {
        service.asServer(ApiKey(apiKey))
    }

    override suspend fun submit(report: ClientBugReport): ClientBugReportSubmissionResult =
        service.submitClientBugReport(ApiKey(apiKey), report)
}

fun embeddedReproducerRuntime(initialState: ServerState, apiKey: String): Resource<EmbeddedReproducerRuntime> =
    resource { consumer ->
        val eventBus = Channel<ScheduledWorkshopEvent>(Channel.UNLIMITED)
        val runtime = EmbeddedReproducerRuntimeImpl(initialState, apiKey, eventBus)
        coroutineScope {
            val eventLoop = launch {
                mainEventLoopWritingTo(
                    serverState = runtime.serverState,
                    eventBus = eventBus,
                    onSoundEvent = {},
                    onEvent = { eventBus.trySend(it) },
                )
            }
            try {
                consumer(runtime)
            } finally {
                eventLoop.cancel()
                eventBus.close()
            }
        }
    }
