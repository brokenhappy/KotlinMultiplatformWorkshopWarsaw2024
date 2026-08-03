package com.kotlinworkshop.test

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.http.*
import kmpworkshop.client.CoroutinePuzzleWorkshopSolutions
import kmpworkshop.client.connectWorkshopService
import kmpworkshop.client.runCoroutinePuzzleClient
import kmpworkshop.common.*
import kmpworkshop.server.mainEventLoopWritingTo
import kmpworkshop.server.rpcServer
import kmpworkshop.server.rpcService
import kmpworkshop.server.workshopService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.rpc.krpc.ktor.client.installKrpc
import workshop.adminaccess.PuzzleState
import workshop.adminaccess.ScheduledWorkshopEvent
import workshop.adminaccess.ServerState
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * The whole [WorkshopCoroutinePuzzlesTestBase] suite, but driven through a *real* kotlinx-rpc transport over a real (loopback)
 * socket, instead of the in-process abstractions [WorkshopCoroutinePuzzleTestWithoutRpcService] and
 * [WorkshopCoroutinePuzzleTestWithRpcService] (which share one coroutine hierarchy with no
 * serialization/transport in between). It deliberately reuses the production entry points on both ends -
 * [rpcServer] (the exact ktor module [kmpworkshop.server.serve] hosts) and [connectWorkshopService] (the exact
 * client wiring [kmpworkshop.client.createWorkshopService] uses) - so the coverage tracks the real bootstrap rather
 * than a hand-rolled copy of it.
 *
 * The in-process suites run each body under [runTestWithRandomizedDispatchOrdering], which shuffles a single
 * virtual-time interleaving across many seeds. That's meaningless here: real ordering is decided by the socket and
 * the Netty/CIO dispatchers, which virtual time can't touch. So [runPuzzleTest] is overridden to run each body once
 * in real time - the transport itself supplies the non-determinism, which is exactly the point: genuine network/async
 * timing gaps between concurrently-issued calls can make the server's `autoBatchedOnQuiescence`-based matching (inside
 * `doCoroutinePuzzleSolveAttempt`'s `channelFlow`) fire before all the concurrent calls it's supposed to batch have
 * actually arrived - something the in-process fake transport can never reproduce.
 */
class WorkshopCoroutinePuzzleTestWithRealRpcTransport : WorkshopCoroutinePuzzleTest() {
    override fun runPuzzleTest(block: suspend CoroutineScope.() -> Unit) {
        runTest(timeout = 60.seconds) { block() }
    }

    override suspend fun runCoroutinePuzzle(
        stage: WorkshopStage,
        solutions: CoroutinePuzzleWorkshopSolutions,
    ): CoroutinePuzzleResultWithHistory = runRealRpcTestClient(stage, solutions)
}

@OptIn(ExperimentalTime::class)
internal suspend fun runRealRpcTestClient(
    stage: WorkshopStage,
    solutions: CoroutinePuzzleWorkshopSolutions,
): CoroutinePuzzleResultWithHistory = coroutineScope {
    val serverState = MutableStateFlow(ServerState(puzzleStates = mapOf(
        stage.name to PuzzleState.Opened(Clock.System.now(), submissions = emptyMap()),
    )))
    val eventBus = Channel<ScheduledWorkshopEvent>()
    val eventLoopJob = launch {
        mainEventLoopWritingTo(
            serverState,
            eventBus = eventBus,
            onCommittedState = {},
            onSoundEvent = {},
            onEvent = { launch { eventBus.send(it) } },
        )
    }

    val server = rpcServer(
        port = 0,
        services = listOf(
            rpcService { workshopService(serverState, onEvent = { launch { eventBus.send(it) } }) },
        ),
    )
    server.startSuspend(wait = false)

    // Matches production's client wiring (see kmpworkshop.client.createWorkshopService); we own the HttpClient here
    // only so it can be closed between the many repeated runs below.
    val httpClient = HttpClient(CIO) {
        installKrpc {
            waitForServices = true
        }
    }
    try {
        val boundPort = server.engine.resolvedConnectors().first().port
        val service = withContext(Dispatchers.IO) {
            httpClient.connectWorkshopService(protocol = URLProtocol.WS, host = "localhost", port = boundPort)
        }

        runCoroutinePuzzleClient(
            puzzleProvider = service.asServer(ApiKey("1234-5678")),
            stage,
            solutions,
        )
    } finally {
        httpClient.close()
        server.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 1000)
        eventLoopJob.cancel()
        eventBus.close()
    }
}

internal fun solutions(
    sumSolution: suspend CoroutineScope.(GetNumberAndSubmit) -> Unit = { error("Unexpected puzzle tested") },
    collectSolution: suspend CoroutineScope.(NumberFlowAndSubmit) -> Unit = { error("Unexpected puzzle tested") },
    maximumAgeFindingTheSecondCoroutineSolution: suspend CoroutineScope.(UserDatabase) -> Unit = { error("Unexpected puzzle tested") },
    mappingLegacyApiCoroutineSolution: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit = { error("Unexpected puzzle tested") },
    exceptionHandlingSolution: suspend CoroutineScope.(ExceptionalApi) -> Unit = { error("Unexpected puzzle tested") },
) = CoroutinePuzzleWorkshopSolutions(
    sumSolution,
    collectSolution,
    maximumAgeFindingTheSecondCoroutineSolution,
    mappingLegacyApiCoroutineSolution,
    exceptionHandlingSolution,
)