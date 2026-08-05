package com.kotlinworkshop.test

import kmpworkshop.client.KotlinBasicsPuzzleSolutions
import kmpworkshop.client.kotlinBasicsPuzzleSolutions
import kmpworkshop.client.runKotlinBasicsPuzzle
import kmpworkshop.common.ApiKey
import kmpworkshop.common.KotlinBasicsPuzzleResult
import kmpworkshop.common.WorkshopStage.KotlinBasicsPuzzleStage
import kmpworkshop.common.asServer
import kmpworkshop.common.asKotlinBasicsPuzzle
import kmpworkshop.server.KotlinBasicsPuzzleType
import org.junit.jupiter.api.Test
import testWorkshopService

/**
 * Kotlin basics counterpart to [WorkshopCoroutinePuzzleTest].
 *
 * Each subclass supplies one puzzle implementation, so the same workshop-solution contract runs against both the
 * direct server implementation and the RPC adapter.
 */
abstract class WorkshopKotlinBasicsPuzzleTest {
    protected abstract suspend fun runPuzzle(
        stage: KotlinBasicsPuzzleStage,
        solutions: KotlinBasicsPuzzleSolutions,
    ): KotlinBasicsPuzzleResult

    @Test
    fun `default solutions are wrong`(): Unit = runTestWithRandomizedDispatchOrdering {
        KotlinBasicsPuzzleStage.entries.forEach { stage ->
            runPuzzle(stage, kotlinBasicsPuzzleSolutions).assertIs<KotlinBasicsPuzzleResult.Failed>()
        }
    }

    @Test
    fun `correct solutions solve every puzzle`(): Unit = runTestWithRandomizedDispatchOrdering {
        val solutions = KotlinBasicsPuzzleSolutions(
            palindromeCheckSolution = { it == it.reversed() },
            minimumAgeSolution = { users -> users.minOf { it.age } },
            oldestUserSolution = { users -> users.maxBy { it.age } },
        )

        KotlinBasicsPuzzleStage.entries.forEach { stage ->
            runPuzzle(stage, solutions).assertEquals(KotlinBasicsPuzzleResult.Success)
        }
    }
}

class KotlinBasicsPuzzleTestWithoutRpcService : WorkshopKotlinBasicsPuzzleTest() {
    override suspend fun runPuzzle(
        stage: KotlinBasicsPuzzleStage,
        solutions: KotlinBasicsPuzzleSolutions,
    ): KotlinBasicsPuzzleResult =
        runKotlinBasicsPuzzle(puzzleProvider = { KotlinBasicsPuzzleType.findPuzzleFor(it).asKotlinBasicsPuzzle() }, stage, solutions,)
}

class KotlinBasicsPuzzleTestWithRpcService : WorkshopKotlinBasicsPuzzleTest() {
    override suspend fun runPuzzle(
        stage: KotlinBasicsPuzzleStage,
        solutions: KotlinBasicsPuzzleSolutions,
    ): KotlinBasicsPuzzleResult = testWorkshopService(serverStateThatOpened(stage)).use { (service) ->
        runKotlinBasicsPuzzle(service.asServer(ApiKey("1234-5678")), stage, solutions)
    }
}
