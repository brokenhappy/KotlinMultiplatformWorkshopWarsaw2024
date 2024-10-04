package kmpworkshop.server

import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.stopServerOnCancellation
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kmpworkshop.common.WorkshopService
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.rpc.RPC
import kotlinx.rpc.serialization.json
import kotlinx.rpc.transport.ktor.server.RPC
import kotlinx.rpc.transport.ktor.server.rpc
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

suspend inline fun <reified Service : RPC> serveSingleService(
    noinline serviceFactory: (CoroutineContext) -> Service,
): Nothing = serveSingleService(Service::class, serviceFactory)

suspend fun <Service : RPC> serveSingleService(
    serviceKClass: KClass<Service>,
    serviceFactory: (CoroutineContext) -> Service,
): Nothing {
    embeddedServer(Netty, port = 8080) {
        install(RPC)

        routing {
            rpc("/${WorkshopService::class.simpleName!!}") {
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
        .stopServerOnCancellation()
        .use { awaitCancellation() }
}

private inline fun <T: Job, R> T.use(f: (T) -> R): R = try {
    f(this)
} finally {
    cancel()
}
