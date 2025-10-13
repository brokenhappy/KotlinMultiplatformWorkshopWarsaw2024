package kmpworkshop.server

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kmpworkshop.common.WorkshopApiService
import kotlinx.coroutines.awaitCancellation
import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.KrpcRoute
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlin.reflect.KClass

suspend fun serve(vararg services: RpcService<*>): Nothing = serve(services.toList())

class RpcService<@Rpc Service : Any>(val klass: KClass<Service>, val factory: () -> Service)

inline fun <@Rpc reified Service : Any> rpcService(noinline factory: () -> Service): RpcService<Service> =
    RpcService(Service::class, factory)

suspend fun serve(services: List<RpcService<*>>): Nothing {
    embeddedServer(Netty, port = 8080) {
        install(Krpc)
        routing {
            get("/healthz") {
                call.respondText("OK")
            }
            rpc("/${WorkshopApiService::class.simpleName!!}") {
                rpcConfig {
                    serialization {
                        json()
                    }
                }

                services.forEach { register(it) }
            }
        }
    }.startSuspend(wait = true)

    println("Extremely questionable event happened, this is definitely not supposed to be printed if ktor APIs were properly designed")
    awaitCancellation()
}

private fun <@Rpc Service : Any> KrpcRoute.register(service: RpcService<Service>) =
    registerService(service.klass, service.factory)