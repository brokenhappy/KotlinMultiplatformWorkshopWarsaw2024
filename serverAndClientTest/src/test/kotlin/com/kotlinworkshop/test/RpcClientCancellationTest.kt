package com.kotlinworkshop.test

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.URLProtocol
import io.ktor.http.encodedPath
import kmpworkshop.common.WorkshopApiService
import kmpworkshop.common.WorkshopStage
import kmpworkshop.server.rpcServer
import kmpworkshop.server.rpcService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.rpc.krpc.ktor.client.KtorRpcClient
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import testWorkshopService
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class RpcClientCancellationTest {
    @Test
    fun `a cancelled RPC transport rejects the next server stream`() = runTest {
        testWorkshopService(serverStateThatOpened(WorkshopStage.CoroutinePuzzleStage.SimpleFlow)).use { (service) ->
            val server = rpcServer(port = 0, services = listOf(rpcService { service }))
            server.startSuspend(wait = false)

            val httpClient = HttpClient(CIO) {
                installKrpc {
                    connector { }
                }
            }
            try {
                val port = server.engine.resolvedConnectors().first().port
                val rpcClient: KtorRpcClient = httpClient.rpc {
                    url {
                        protocol = URLProtocol.WS
                        host = "localhost"
                        this.port = port
                        encodedPath = "rpc"
                    }
                    rpcConfig {
                        serialization { json() }
                    }
                }
                val workshopService = rpcClient.withService<WorkshopApiService>()

                withContext(Dispatchers.IO) {
                    workshopService.currentStage().first()
                }

                rpcClient.close()

                val failure = assertFailsWith<IllegalStateException> {
                    withContext(Dispatchers.IO) {
                        workshopService.currentStage().first()
                    }
                }
                assertContains(failure.message.orEmpty(), "RpcClient was cancelled")
            } finally {
                httpClient.close()
                server.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 1_000)
            }
        }
    }
}
