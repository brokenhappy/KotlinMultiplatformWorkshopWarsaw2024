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
val workshopService: WorkshopApiService get() = _workshopService ?: createService().also { _workshopService = it }

private fun createService(): WorkshopApiService = runBlocking {
    val ktorClient = HttpClient {
        installKrpc {
            waitForServices = true
        }
    }

    val client: KtorRpcClient = ktorClient.rpc {
        url {
//            host = "192.168.0.67"
//            host = "10.0.2.2"
            host = "172.20.10.2"
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
        WorkshopStage.SimpleFlow,
        WorkshopStage.CollectLatest,
        WorkshopStage.PalindromeCheckTask,
        WorkshopStage.FindMinimumAgeOfUserTask,
        WorkshopStage.FindOldestUserTask -> Text("""
            Hmm, we went back to one of the non UI tasks...
        """.trimIndent())
    }
}