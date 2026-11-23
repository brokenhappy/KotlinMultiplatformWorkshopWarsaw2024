package kmpworkshop.server

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.timeout
import kotlinx.coroutines.awaitCancellation
import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.KrpcRoute
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.days

// Reports carry up to 40 MiB of Base64 plus padding. KRPC wraps the payload in a JSON string,
// which can expand diagnostic text to seven bytes per UTF-16 code unit. Leave room for both
// sets of diagnostics and the state snapshot carried by the admin report stream.
internal const val WorkshopMaxWebSocketFrameSize = 80L * 1024 * 1024

suspend fun serve(vararg services: RpcService<*>): Nothing = serve(services.toList())

class RpcService<@Rpc Service : Any>(val klass: KClass<Service>, val factory: () -> Service)

inline fun <@Rpc reified Service : Any> rpcService(noinline factory: () -> Service): RpcService<Service> =
    RpcService(Service::class, factory)

suspend fun serve(services: List<RpcService<*>>): Nothing {
    rpcServer(services = services).startSuspend(wait = true)

    println("Extremely questionable event happened, this is definitely not supposed to be printed if ktor APIs were properly designed")
    awaitCancellation()
}

/**
 * Builds the workshop's RPC server without starting it, so callers control the lifecycle (start/stop) themselves.
 * [port] defaults to the production port, but tests can pass `0` to bind an ephemeral port and read it back via
 * `engine.resolvedConnectors()`. This is the same wiring [serve] runs in production, so exercising it keeps
 * end-to-end tests honest about the real ktor + kotlinx-rpc setup.
 */
fun rpcServer(port: Int = 8080, services: List<RpcService<*>>) =
    embeddedServer(Netty, port = port) {
        install(WebSockets) {
            maxFrameSize = WorkshopMaxWebSocketFrameSize
        }
        install(Krpc)
        routing {
            get("/healthz") {
                call.respondText("OK")
            }
            rpc("rpc") {
                rpcConfig {
                    serialization {
                        json()
                    }
                }
                timeoutMillis = 999_999_999_999
                timeout = 999_999_999_999.days

                services.forEach { register(it) }
            }
        }
    }

private fun <@Rpc Service : Any> KrpcRoute.register(service: RpcService<Service>) =
    registerService(service.klass, service.factory)
