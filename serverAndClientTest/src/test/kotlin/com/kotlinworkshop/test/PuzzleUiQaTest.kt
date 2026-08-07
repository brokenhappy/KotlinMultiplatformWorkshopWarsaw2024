@file:OptIn(ExperimentalTime::class, ExperimentalTestApi::class)

package com.kotlinworkshop.test

import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kmpworkshop.client.CoroutinePuzzleWorkshopSolutions
import kmpworkshop.client.WorkshopClient
import kmpworkshop.client.kotlinBasicsPuzzleSolutions
import kmpworkshop.client.workshopSolutions
import kmpworkshop.common.ApiKey
import kmpworkshop.common.CoroutinePuzzle
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.CoroutinePuzzleSolveState
import kmpworkshop.common.KotlinBasicsPuzzle
import kmpworkshop.common.KotlinBasicsPuzzleResult
import kmpworkshop.common.WorkshopStage
import kmpworkshop.common.WorkshopServer
import kmpworkshop.common.WorkshopStage.KotlinBasicsPuzzleStage
import kmpworkshop.common.WorkshopStage.CoroutinePuzzleStage
import kmpworkshop.common.asServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import testWorkshopService
import workshop.adminaccess.PuzzleState
import workshop.adminaccess.ServerState
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class PuzzleUiQaTest {
    @Test
    fun `a real puzzle attempt is rendered as a completed timeline`(): Unit = runPuzzleUiTest(
        stage = CoroutinePuzzleStage.SumOfTwoIntsFast,
        solutions = workshopSolutions.copy(
            sumSolution = { api ->
                val first = async { api.getNumber() }
                val second = async { api.getNumber() }
                api.submit(first.await() + second.await())
            },
        ),
    ) {
        onNodeWithTag("puzzle-status").assertTextContains("The puzzle was solved")
        onNodeWithText("Call timeline").assertIsDisplayed()
        onNodeWithTag("timeline-marker-1-0").assertIsDisplayed()
        onNodeWithTag("puzzle-run-button").assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 10_000) {
            runCatching {
                onNodeWithText("Yaay! You solved it again! Perhaps you could look around and see if some of your peers would like your help? :))")
                    .assertIsDisplayed()
            }.isSuccess
        }
    }

    @Test
    fun `a throwing solution is shown as a failure instead of crashing the UI`(): Unit = runPuzzleUiTest(
        stage = CoroutinePuzzleStage.SumOfTwoIntsFast,
        solutions = workshopSolutions.copy(
            sumSolution = { error("deliberate test failure") },
        ),
    ) {
        onNodeWithTag("puzzle-status").assertTextContains("Test failed: deliberate test failure")
        onNodeWithTag("puzzle-run-button").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `an incorrect solution renders protocol guidance and its timeline`() = runPuzzleUiTest(
        stage = CoroutinePuzzleStage.SumOfTwoIntsSlow,
        solutions = workshopSolutions.copy(
            sumSolution = { api -> api.submit(api.getNumber()) },
        ),
    ) {
        waitForIdle()
        onNodeWithText(
            "Currently the expected action is Call getNumber(): Int.\n" +
                "But instead you were doing Call submit(number: Int): Unit.",
        ).assertIsDisplayed()
        onNodeWithText("Call timeline").assertIsDisplayed()
    }

    @Test
    fun `a coroutine stage starts with an empty timeline`(): Unit = runComposeUiTest {
        val server = UiTestWorkshopServer(
            MutableStateFlow(CoroutinePuzzleStage.SumOfTwoIntsSlow),
        )
        setContent {
            MaterialTheme { Surface { WorkshopClient(server) } }
        }

        onNodeWithTag("puzzle-run-button").assertIsDisplayed()
        onNodeWithText("The timeline will appear here as soon as your solution makes its first call.")
            .assertIsDisplayed()
    }

    @Test
    fun `registration changes into a puzzle screen when the server opens a stage`(): Unit = runComposeUiTest {
        val stage = MutableStateFlow<WorkshopStage>(WorkshopStage.Registration)
        val server = UiTestWorkshopServer(stage)
        setContent {
            MaterialTheme { Surface { WorkshopClient(server) } }
        }

        onNodeWithText("The workshop is in registration. Waiting for the host to open a puzzle…")
            .assertIsDisplayed()
        stage.value = CoroutinePuzzleStage.SumOfTwoIntsSlow
        waitUntil(timeoutMillis = 10_000) {
            runCatching { onNodeWithTag("puzzle-run-button").assertExists() }.isSuccess
        }
        onAllNodesWithText("The workshop is in registration. Waiting for the host to open a puzzle…")
            .assertCountEquals(0)
        onNodeWithTag("puzzle-run-button").assertIsDisplayed()
    }

    @Test
    fun `a coroutine puzzle can be run again after leaving and returning to its stage`(): Unit = runComposeUiTest {
        val stage = MutableStateFlow<WorkshopStage>(CoroutinePuzzleStage.SumOfTwoIntsSlow)
        val server = UiTestWorkshopServer(stage)
        setContent {
            MaterialTheme { Surface { WorkshopClient(server) } }
        }

        onNodeWithTag("puzzle-run-button").performClick()
        waitUntil(timeoutMillis = 10_000) {
            runCatching { onNodeWithText("The puzzle was solved").assertIsDisplayed() }.isSuccess
        }

        stage.value = CoroutinePuzzleStage.SumOfTwoIntsFast
        waitUntil(timeoutMillis = 10_000) {
            runCatching { onNodeWithText("Sum Of Two Ints Fast").assertIsDisplayed() }.isSuccess
        }
        stage.value = CoroutinePuzzleStage.SumOfTwoIntsSlow
        waitUntil(timeoutMillis = 10_000) {
            runCatching { onNodeWithText("Sum Of Two Ints Slow").assertIsDisplayed() }.isSuccess
        }

        onNodeWithTag("puzzle-run-button").assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 10_000) {
            runCatching { onNodeWithText("The puzzle was solved").assertIsDisplayed() }.isSuccess
        }
    }

    @Test
    fun `a Kotlin basics puzzle provider success is shown without using the production network singleton`(): Unit = runComposeUiTest {
        var receivedStage: KotlinBasicsPuzzleStage? = null
        val server = UiTestWorkshopServer(
            MutableStateFlow(KotlinBasicsPuzzleStage.PalindromeCheckTask),
            puzzleFactory = { stage ->
                KotlinBasicsPuzzle { solution ->
                    solution(Json.parseToJsonElement("\"racecar\""))
                    receivedStage = stage
                    KotlinBasicsPuzzleResult.Success
                }
            },
        )
        setContent {
            MaterialTheme {
                Surface {
                    WorkshopClient(server)
                }
            }
        }

        waitUntil(timeoutMillis = 10_000) {
            receivedStage != null
        }
        waitForIdle()
        assert(receivedStage == KotlinBasicsPuzzleStage.PalindromeCheckTask)
        onNodeWithTag("puzzle-status").assertIsDisplayed()
        onNodeWithText("Palindrome Check Task").assertIsDisplayed()
    }

    @Test
    fun `a Kotlin basics solution failure is rendered instead of crashing the screen`(): Unit = runComposeUiTest {
        val server = UiTestWorkshopServer(
            MutableStateFlow(KotlinBasicsPuzzleStage.FindOldestUserTask),
            puzzleFactory = {
                KotlinBasicsPuzzle { solution ->
                    solution(Json.parseToJsonElement("""[{"name":"John","age":18}]"""))
                    KotlinBasicsPuzzleResult.Success
                }
            },
        )
        setContent {
            MaterialTheme {
                Surface {
                    WorkshopClient(
                        server,
                        kotlinBasicsSolutions = kotlinBasicsPuzzleSolutions.copy(
                            oldestUserSolution = { error("code runner failed") },
                        ),
                    )
                }
            }
        }

        waitUntil(timeoutMillis = 10_000) {
            runCatching {
                onNodeWithTag("puzzle-status").assertTextContains("Test failed: code runner failed")
            }.isSuccess
        }
        onNodeWithText("Find Oldest User Task").assertIsDisplayed()
    }

}

private fun runPuzzleUiTest(
    stage: CoroutinePuzzleStage,
    solutions: CoroutinePuzzleWorkshopSolutions,
    assertions: ComposeUiTest.() -> Unit,
): Unit = runTest {
    testWorkshopService(serverStateThatOpened(stage)).use { (service) ->
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    Surface { WorkshopClient(service.asServer(ApiKey("test")), solutions) }
                }
            }

            onNodeWithTag("puzzle-run-button").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis = 10_000) {
                runCatching { onNodeWithTag("puzzle-status").assertExists() }.isSuccess
            }
            waitUntil(timeoutMillis = 10_000) {
                runCatching {
                    onNodeWithTag("puzzle-status").assertTextContains("Running test…")
                    false
                }.getOrDefault(true)
            }
            assertions()
        }
    }
}

private class UiTestWorkshopServer(
    private val stages: MutableStateFlow<WorkshopStage>,
    private val puzzleFactory: (KotlinBasicsPuzzleStage) -> KotlinBasicsPuzzle = { stage ->
        error("The UI test did not expect a Kotlin basics puzzle for $stage")
    },
) : WorkshopServer {
    override fun currentStage(): Flow<WorkshopStage> = stages

    override fun kotlinBasicsPuzzle(stage: KotlinBasicsPuzzleStage): KotlinBasicsPuzzle = puzzleFactory(stage)

    override fun coroutinePuzzle(stage: CoroutinePuzzleStage): CoroutinePuzzle =
        CoroutinePuzzle { flowOf(CoroutinePuzzleSolveState.Completed(CoroutinePuzzleSolutionResult.Success)) }
}

internal fun serverStateThatOpened(stage: CoroutinePuzzleStage): ServerState = ServerState(
    currentStage = stage,
    puzzleStates = mapOf(stage.name to PuzzleState.Opened(Clock.System.now(), emptyMap())),
)
