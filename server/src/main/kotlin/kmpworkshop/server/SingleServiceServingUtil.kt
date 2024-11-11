package kmpworkshop.server

import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.stopServerOnCancellation
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kmpworkshop.common.WorkshopApiService
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.rpc.RemoteService
import kotlinx.rpc.krpc.ktor.server.RPC
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

suspend inline fun <reified Service : RemoteService> serveSingleService(
    noinline serviceFactory: (CoroutineContext) -> Service,
): Nothing = serveSingleService(Service::class, serviceFactory)

suspend fun <Service : RemoteService> serveSingleService(
    serviceKClass: KClass<Service>,
    serviceFactory: (CoroutineContext) -> Service,
): Nothing {
    embeddedServer(Netty, port = 8080) {
        install(RPC)

        routing {
            rpc("/${WorkshopApiService::class.simpleName!!}") {
                rpcConfig {
                    serialization {
                        json()
                    }
                }

                registerService(serviceKClass, serviceFactory)
            }
        }
        println("Server running")
    }.apply { start(wait = true) }
        .run { engine.stopServerOnCancellation(application) }
        .use { awaitCancellation() }
}

private inline fun <T: Job, R> T.use(f: (T) -> R): R = try {
    f(this)
} finally {
    cancel()
}
