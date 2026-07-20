package kmpworkshop.client

import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.ktor.client.*
import io.ktor.http.*
import kmpworkshop.common.*
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.krpc.ktor.client.KtorRpcClient
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService

// This is a hacky solution just so I can keep things simple for the scope of the workshop.
// Please don't use global state like this yourself.
// It's too big of a topic to explain why in these comments.
// Ask me if you're curious why this is not recommended in production environments!
private var _workshopService: WorkshopApiService? = null
val workshopService: WorkshopApiService get() = _workshopService ?: createWorkshopService().also { _workshopService = it }

/**
 * Connects to the workshop server and returns a [WorkshopApiService] over a real kotlinx-rpc transport, owning the
 * lifetime of the underlying ktor client for the rest of the process (as befits the app's singleton service).
 */
fun createWorkshopService(): WorkshopApiService = runBlocking {
    HttpClient {
        installKrpc {
            waitForServices = true
        }
    }.connectWorkshopService()
}

/**
 * Wires this ktor client up to the workshop RPC service. The connection parameters default to the production
 * server, but are injectable so that end-to-end tests can point the *same* client wiring at a locally hosted
 * server (e.g. `URLProtocol.WS`, `localhost`, an ephemeral port) - and, by supplying their own [HttpClient], own
 * its lifecycle so it can be closed between runs instead of re-implementing the client setup.
 */
suspend fun HttpClient.connectWorkshopService(
    protocol: URLProtocol = URLProtocol.WSS,
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

@Composable
fun ClientEntryPoint() {
    ClientEntryPoint(
        server = remember {
            workshopService.asServer(ApiKey(clientApiKey ?: error("You need to finish registration first!")))
        },
    )
}

@Composable
fun ClientEntryPoint(
    server: WorkshopServer,
) {
    val stage by remember { server.currentStage() }.collectAsState(initial = WorkshopStage.Registration)
    when (stage) {
        WorkshopStage.Registration -> Text("""
            The host went back to the Registration phase.
            Most likely the host is configuring something.
            A moment of patience please.
            
            (Maybe you can take these seconds to help your peers? :) )
        """.trimIndent())
        WorkshopStage.SumOfTwoIntsSlow,
        WorkshopStage.SumOfTwoIntsFast,
        WorkshopStage.FindMaximumAgeCoroutines,
        WorkshopStage.FastFindMaximumAgeCoroutines,
        WorkshopStage.SimpleFlow,
        WorkshopStage.CollectLatest,
        WorkshopStage.MappingFromLegacyApisStepOne,
        WorkshopStage.MappingFromLegacyApisStepTwo,
        WorkshopStage.MappingFromLegacyApisStepThree,
        WorkshopStage.MappingFromLegacyApisStepFour,
        WorkshopStage.PalindromeCheckTask,
        WorkshopStage.FindMinimumAgeOfUserTask,
        WorkshopStage.FindOldestUserTask -> Text("""
            Hmm, we went back to one of the non UI tasks...
        """.trimIndent())
    }
}