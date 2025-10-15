package kmpworkshop.server

import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import kotlin.test.assertEquals

class CoroutinePuzzlesTest {
    @Test
    fun `empty solutions are wrong`(): Unit = runBlocking {
        doSimpleSumPuzzle { }.assertIsNotOk()
        doTimedSumPuzzle { }.assertIsNotOk()
        doSimpleCollectPuzzle { }.assertIsNotOk()
        doCollectLatestPuzzle { }.assertIsNotOk()
    }

    @Test
    fun `regular collect must fail collect latest puzzle`(): Unit = runBlocking {
        doCollectLatestPuzzle { api ->
            api.numbers().collect { api.submit(it) }
        }
            .assertIs<CoroutinePuzzleSolutionResult.Failure> { "Regular collect must fail collect latest puzzle" }
            .description
            .assert({ "cancel" in it.lowercase() }) { "Message must mention cancellation" }
            .assert({ "submit" in it.lowercase() }) { "Message must mention submit" }
    }

    @Test
    fun `collectLatest correct solution`(): Unit = runBlocking {
        doCollectLatestPuzzle { api ->
            api.numbers().collectLatest { api.submit(it) }
        }.assertIsOk()
    }

    @Test
    fun `simple flow puzzle does not need collect latest`(): Unit = runBlocking {
        doSimpleCollectPuzzle { api ->
            api.numbers().collect { api.submit(it) }
        }.assertIsOk()
    }

    @Test
    fun `simple flow puzzle might pass with collect latest`(): Unit = runBlocking {
        // Not strictly needed behavior, but I keep it in here to increase coverage
        doSimpleCollectPuzzle { api ->
            api.numbers().collectLatest { api.submit(it) }
        }.assertIsOk()
    }

    @Test
    fun `simple sum correct solution`(): Unit = runBlocking {
        doSimpleSumPuzzle { api ->
            api.submit(api.getNumber() + api.getNumber())
        }.assertIsOk()
    }

    @Test
    fun `sum of too many numbers`(): Unit = runBlocking {
        doSimpleSumPuzzle { api ->
            api.submit(api.getNumber() + api.getNumber() + api.getNumber())
        }.assertIsNotOk()
        doTimedSumPuzzle { api ->
            val firstSum = async { api.getNumber() }
            val secondSum = async { api.getNumber() }
            api.submit(api.getNumber() + firstSum.await() + secondSum.await())
        }.assertIsNotOk()
    }

    @Test
    fun `submitting incorrect sum is not ok`(): Unit = runBlocking {
        doSimpleSumPuzzle { api ->
            api.submit(api.getNumber())
        }.assertIsNotOk()
        doSimpleSumPuzzle { api ->
            api.submit(api.getNumber() + api.getNumber() + 1)
        }.assertIsNotOk()
        doSimpleSumPuzzle { api ->
            api.submit(13)
        }.assertIsNotOk()
    }

    @Test
    fun `submitting in parallel is ok`(): Unit = runBlocking {
        doSimpleSumPuzzle { api ->
            val firstSum = async { api.getNumber() }
            api.submit(api.getNumber() + firstSum.await())
        }.assertIsOk()
    }

    @Test
    fun `timed sum correct solution`(): Unit = runBlocking {
        doTimedSumPuzzle { api ->
            val firstSum = async { api.getNumber() }
            api.submit(api.getNumber() + firstSum.await())
        }.assertIsOk()
    }

    @Test
    fun `timed sum too slow solution fails`(): Unit = runBlocking {
        doTimedSumPuzzle { api ->
            api.submit(api.getNumber() + api.getNumber())
        }
            .assertIs<CoroutinePuzzleSolutionResult.Failure> { "Regular collect must fail collect latest puzzle" }
            .description
            .assert({ "slow" in it.lowercase() }) { "Message must mention it being slow" }
            .assert({ "1.8" in it.lowercase() }) { "Message must mention expected time" }
    }
}

private fun CoroutinePuzzleSolutionResult.assertIsOk(): Unit = when (this) {
    is CoroutinePuzzleSolutionResult.Failure -> fail { this.description }
    CoroutinePuzzleSolutionResult.Success -> { /** All OK! */ }
}

private fun CoroutinePuzzleSolutionResult.assertIsNotOk() {
    assertIs<CoroutinePuzzleSolutionResult.Failure> { "Puzzle succeeded unexpectedly" }
}

internal inline fun <reified T> Any?.assertIs(message: (Any?) -> String): T =
    if (this is T) this else kotlin.test.fail(message(this))

internal inline fun Any?.assertIs(other: Any?, message: (Any?) -> String) {
    assertEquals(this, message(this))
}

internal inline fun <T> T.assert(test: (T) -> Boolean, message: (T) -> String): T =
    this.also { if (!test(this)) kotlin.test.fail(message(this) + "\nActual value was: $this") }
