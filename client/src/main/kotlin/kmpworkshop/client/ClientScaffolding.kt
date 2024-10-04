package kmpworkshop.client

import io.ktor.client.*
import io.ktor.http.*
import kmpworkshop.common.WorkshopService
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
private var _workshopService: WorkshopService? = null
val workshopService: WorkshopService get() = _workshopService ?: createService().also { _workshopService = it }

private fun createService(): WorkshopService = runBlocking {
    val ktorClient = HttpClient {
        installRPC {
            waitForServices = true
        }
    }

    val client: KtorRPCClient = ktorClient.rpc {
        url {
            host = "localhost"
            port = 8080
            encodedPath = WorkshopService::class.simpleName!!
        }

        rpcConfig {
            serialization {
                json()
            }
        }
    }

    client.withService<WorkshopService>()
        .also { println("Client connected!") }
}