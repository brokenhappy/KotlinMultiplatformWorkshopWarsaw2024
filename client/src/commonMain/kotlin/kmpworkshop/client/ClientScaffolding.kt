package kmpworkshop.client

import io.ktor.client.*
import io.ktor.http.*
import kmpworkshop.common.WorkshopApiService
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