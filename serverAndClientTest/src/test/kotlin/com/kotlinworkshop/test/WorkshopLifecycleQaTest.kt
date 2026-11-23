package com.kotlinworkshop.test

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.websocket.WebSocketException
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import kmpworkshop.client.connectWorkshopService
import kmpworkshop.client.kotlinBasicsPuzzleSolutions
import kmpworkshop.client.runKotlinBasicsPuzzle
import kmpworkshop.client.withWorkshopService
import kmpworkshop.common.ApiKey
import kmpworkshop.common.ApiKeyRegistrationResult
import kmpworkshop.common.KotlinBasicsPuzzleResult
import kmpworkshop.common.NameVerificationResult
import kmpworkshop.common.WorkshopApiService
import kmpworkshop.common.WorkshopInvitationCodeHeader
import kmpworkshop.common.WorkshopStage
import kmpworkshop.common.asServer
import kmpworkshop.common.coroutinesToLoom
import kmpworkshop.common.workshopInvitationCodeHeaderValue
import kmpworkshop.server.RpcService
import kmpworkshop.server.adminAccess
import kmpworkshop.server.rpcServer
import kmpworkshop.server.rpcService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import testWorkshopService
import workshop.adminaccess.AdminAccess
import workshop.adminaccess.Participant
import workshop.adminaccess.PuzzleStartEvent
import workshop.adminaccess.PuzzleState
import workshop.adminaccess.ServerState
import workshop.adminaccess.StageChangeEvent
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class WorkshopLifecycleQaTest {
    @Test
    fun `a participant registers and the host sees their first successful submission`() = runTest(timeout = 30.seconds) {
        val host = "127.0.0.1"
        val password = "qa-fixture-password"
        val invitationCode = "qa-fixture-Łódź-✓"
        testWorkshopService(ServerState()).use { (workshopService, onEvent, state) ->
            val server = rpcServer(
                invitationCode = invitationCode,
                port = 0,
                host = host,
                services = listOf<RpcService<*>>(
                    rpcService<WorkshopApiService> { workshopService },
                    rpcService<AdminAccess> { adminAccess(password, state, onEvent, emptyFlow(), emptyFlow()) },
                ),
            )
            try {
                server.startSuspend(wait = false)
                val port = server.engine.resolvedConnectors().single().port
                HttpClient(CIO) { installKrpc { connector {} } }.use { client ->
                    val health = client.get("http://$host:$port/healthz")
                    assertEquals(HttpStatusCode.OK, health.status)
                    assertEquals("OK", health.bodyAsText())

                    val rpcUrl = "ws://$host:$port/rpc"
                    for (code in listOf(null, "wrong-code")) {
                        val failure = assertFailsWith<WebSocketException> {
                            client.webSocketSession(rpcUrl) {
                                if (code != null) header(WorkshopInvitationCodeHeader, workshopInvitationCodeHeaderValue(code))
                            }
                        }
                        assertContains(failure.message.orEmpty(), "but was 401")
                    }

                    val workshop = coroutinesToLoom { client.connectWorkshopService(invitationCode, URLProtocol.WS, host, port) }
                    val admin = coroutinesToLoom {
                        client.rpc(rpcUrl) {
                            header(WorkshopInvitationCodeHeader, workshopInvitationCodeHeaderValue(invitationCode))
                            rpcConfig { serialization { json() } }
                        }.withService<AdminAccess>()
                    }
                    val hostState = admin.serverState(password)
                    assertEquals(WorkshopStage.Registration, workshop.currentStage().first())
                    assertTrue(hostState.first().participants.isEmpty())

                    val name = "QA Participant"
                    val rejected = assertFailsWith<ClientRequestException> {
                        withWorkshopService("wrong-code", URLProtocol.WS, host, port) { it.registerApiKeyFor(name) }
                    }
                    assertEquals(HttpStatusCode.Unauthorized, rejected.response.status)

                    val registration = withWorkshopService(invitationCode, URLProtocol.WS, host, port) {
                        it.registerApiKeyFor(name)
                    }
                    val key = assertIs<ApiKeyRegistrationResult.Success>(registration).key
                    assertEquals(ApiKeyRegistrationResult.NameAlreadyExists, workshop.registerApiKeyFor(name))
                    assertEquals(NameVerificationResult.ApiKeyDoesNotExist, workshop.verifyRegistration(ApiKey("unused")))
                    val verification = withWorkshopService(invitationCode, URLProtocol.WS, host, port) {
                        it.verifyRegistration(key)
                    }
                    assertEquals(NameVerificationResult.Success, verification)
                    assertEquals(NameVerificationResult.AlreadyRegistered, workshop.verifyRegistration(key))
                    val registered = hostState.first()
                    assertEquals(listOf(Participant(name, key)), registered.participants)
                    assertTrue(registered.unverifiedParticipants.isEmpty())

                    val player = workshop.asServer(key)
                    val stage = WorkshopStage.KotlinBasicsPuzzleStage.PalindromeCheckTask
                    admin.fire(password, StageChangeEvent(stage))
                    player.currentStage().first { it == stage }
                    admin.fire(password, PuzzleStartEvent(stage.name, Clock.System.now()))
                    hostState.first { it.puzzleStates[stage.name] is PuzzleState.Opened }

                    val wrongSolution = kotlinBasicsPuzzleSolutions.copy(palindromeCheckSolution = { it != it.reversed() })
                    assertIs<KotlinBasicsPuzzleResult.Failed>(runKotlinBasicsPuzzle(player, stage, wrongSolution))
                    val afterWrongAnswer = assertIs<PuzzleState.Opened>(hostState.first().puzzleStates[stage.name])
                    assertTrue(afterWrongAnswer.submissions.isEmpty())

                    val correctSolution = kotlinBasicsPuzzleSolutions.copy(palindromeCheckSolution = { it == it.reversed() })
                    assertEquals(KotlinBasicsPuzzleResult.Success, runKotlinBasicsPuzzle(player, stage, correctSolution))
                    val completed = assertIs<PuzzleState.Opened>(hostState.first().puzzleStates[stage.name])
                    assertEquals(setOf(key.stringRepresentation), completed.submissions.keys)

                    assertIs<KotlinBasicsPuzzleResult.CustomFailure>(runKotlinBasicsPuzzle(player, stage, correctSolution))
                    assertEquals(completed, hostState.first().puzzleStates[stage.name])
                }
            } finally {
                withContext(NonCancellable) { server.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 1_000) }
            }
        }
    }
}
