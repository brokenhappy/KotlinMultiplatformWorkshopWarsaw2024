package kmpworkshop.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.URLProtocol
import io.ktor.http.encodedPath
import kmpworkshop.common.WorkshopStage
import kmpworkshop.common.superSecretFallbackPassword
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import org.junit.jupiter.api.Test
import workshop.adminaccess.AdminAccess
import workshop.adminaccess.ScheduledWorkshopEvent
import workshop.adminaccess.ServerState
import workshop.adminaccess.StageChangeEvent
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AdminStageChangesOverRpcTest {
    @Test
    fun `delayed concurrent stage changes leave the server event loop responsive`() = runTest(timeout = 10.seconds) {
        val serverState = MutableStateFlow(ServerState())
        val eventBus = Channel<ScheduledWorkshopEvent>(Channel.UNLIMITED)
        val eventLoopScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        eventLoopScope.launch {
            mainEventLoopWritingTo(
                serverState = serverState,
                eventBus = eventBus,
                onSoundEvent = {},
                onEvent = eventBus::trySend,
            )
        }
        val rpcServer = rpcServer(
            port = 0,
            services = listOf(
                rpcService {
                    adminAccess(
                        serverState = serverState,
                        onEvent = eventBus::trySend,
                        sounds = MutableSharedFlow(),
                        clientBugReports = emptyFlow(),
                    )
                },
            ),
        )
        rpcServer.startSuspend(wait = false)
        val client = HttpClient(CIO) { installKrpc { connector { } } }

        try {
            val port = rpcServer.engine.resolvedConnectors().first().port
            val admin = withContext(Dispatchers.IO) {
                client.rpc {
                    url {
                        protocol = URLProtocol.WS
                        host = "localhost"
                        this.port = port
                        encodedPath = "rpc"
                    }
                    rpcConfig { serialization { json() } }
                }.withService<AdminAccess>()
            }
            val password = superSecretFallbackPassword()
            val firstStage = WorkshopStage.KotlinBasicsPuzzleStage.PalindromeCheckTask
            val secondStage = WorkshopStage.KotlinBasicsPuzzleStage.FindMinimumAgeOfUserTask
            val finalStage = WorkshopStage.KotlinBasicsPuzzleStage.FindOldestUserTask

            repeat(4) {
                admin.fire(password, StageChangeEvent(firstStage))
                awaitStage(serverState, firstStage)
                admin.fire(password, StageChangeEvent(WorkshopStage.Registration))
                awaitStage(serverState, WorkshopStage.Registration)
            }

            coroutineScope {
                launch { admin.fire(password, StageChangeEvent(firstStage)) }
                launch {
                    delay(250.milliseconds)
                    admin.fire(password, StageChangeEvent(secondStage))
                }
            }
            awaitStage(serverState, secondStage)

            delay(250.milliseconds)
            admin.fire(password, StageChangeEvent(finalStage))
            awaitStage(serverState, finalStage)

            assertEquals(finalStage, serverState.value.currentStage)
        } finally {
            client.close()
            rpcServer.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 1_000)
            eventLoopScope.cancel()
            eventBus.close()
        }
    }

    private suspend fun awaitStage(serverState: MutableStateFlow<ServerState>, stage: WorkshopStage) {
        withTimeout(2.seconds) { serverState.first { it.currentStage == stage } }
    }
}
