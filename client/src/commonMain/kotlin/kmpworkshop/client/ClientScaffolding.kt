package kmpworkshop.client

import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import io.ktor.client.*
import io.ktor.http.*
import kmpworkshop.common.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.serialization.json
import kotlinx.rpc.transport.ktor.client.KtorRPCClient
import kotlinx.rpc.transport.ktor.client.installRPC
import kotlinx.rpc.transport.ktor.client.rpc
import kotlinx.rpc.transport.ktor.client.rpcConfig
import kotlinx.rpc.withService

// This is a hacky solution just so I can keep things simple for the scope of the workshop.
// Please don't use global state like this yourself.
// It's too big of a topic to explain why in these comments.
// Ask me if you're curious why this is not recommended in production environments!
private var _workshopService: WorkshopApiService? = null
val workshopService: WorkshopApiService get() = _workshopService ?: createService().also { _workshopService = it }

private fun createService(): WorkshopApiService = runBlocking {
    val ktorClient = HttpClient {
        installRPC {
            waitForServices = true
        }
    }

    val client: KtorRPCClient = ktorClient.rpc {
        url {
            host = "localhost"
//            host = "10.0.2.2"
            port = 8080
            encodedPath = WorkshopApiService::class.simpleName!!
        }

        rpcConfig {
            serialization {
                json()
            }
        }
    }

    client.withService<WorkshopApiService>()
}

@Composable
fun ClientEntryPoint(
    sliderGameSolution: @Composable () -> Unit = { SliderGameClient() },
    pressiveGameSolution: @Composable () -> Unit = { AdaptingBackground { PressiveGame() } },
    discoGameSolution: @Composable (DiscoGameServer) -> Unit = { DiscoGame(it) },
) {
    ClientEntryPoint(
        server = remember {
            workshopService.asServer(ApiKey(clientApiKey ?: error("You need to finish registration first!")))
        },
        sliderGameSolution,
        pressiveGameSolution,
        discoGameSolution,
    )
}

@Composable
fun ClientEntryPoint(
    server: WorkshopServer,
    sliderGameSolution: @Composable () -> Unit = { SliderGameClient() },
    pressiveGameSolution: @Composable () -> Unit = { AdaptingBackground { PressiveGame() } },
    discoGameSolution: @Composable (DiscoGameServer) -> Unit = { DiscoGame(it) },
) {
    val stage by remember { server.currentStage() }.collectAsState(initial = WorkshopStage.SliderGameStage)
    when (stage) {
        WorkshopStage.Registration -> Text("""
            The host went back to the Registration phase.
            Most likely the host is configuring something.
            A moment of patience please.
            
            (Maybe you can take these seconds to help your peers? :) )
        """.trimIndent())
        WorkshopStage.PalindromeCheckTask,
        WorkshopStage.FindMinimumAgeOfUserTask,
        WorkshopStage.FindOldestUserTask -> Text("""
            Hmm, we went back to one of the non UI tasks...
        """.trimIndent())
        WorkshopStage.SliderGameStage -> sliderGameSolution()
        WorkshopStage.PressiveGameStage -> pressiveGameSolution()
        WorkshopStage.DiscoGame -> discoGameSolution(object: DiscoGameServer {
            override fun backgroundColors(): Flow<Color> = server.discoGameBackground().map { it.toComposeColor() }
            override fun instructions(): Flow<DiscoGameInstruction?> = server.discoGameInstructions()
            override suspend fun submitGuess() {
                server.discoGamePress()
            }
        })
    }
}