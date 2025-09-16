package kmpworkshop.server

import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.stopServerOnCancellation
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kmpworkshop.common.WorkshopApiService
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

suspend inline fun <@Rpc reified Service : Any> serveSingleService(
    noinline serviceFactory: () -> Service,
): Nothing = serveSingleService(Service::class, serviceFactory)

suspend fun <@Rpc Service : Any> serveSingleService(
    serviceKClass: KClass<Service>,
    serviceFactory: () -> Service,
): Nothing {
    embeddedServer(Netty, port = 8080) {
        install(Krpc)
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
    }.startSuspend(wait = true)

    println("Extremely questionable event happened, this is definitely not supposed to be printed if ktor APIs were properly designed")
    awaitCancellation()
}
