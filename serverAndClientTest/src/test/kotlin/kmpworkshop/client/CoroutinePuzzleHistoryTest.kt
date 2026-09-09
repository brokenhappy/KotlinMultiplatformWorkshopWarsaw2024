@file:Suppress("SameParameterValue")

package kmpworkshop.client

import kmpworkshop.common.*
import kmpworkshop.common.CoroutinePuzzleCallPayload.Outcome
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.Test

private object TestApis : EndpointDescriptorRegistry() {
    val endpoint by descriptor<Unit, Unit>("endpoint")
    val fetchUser by descriptor<Unit, Unit>("fetchUser")
    val loadAvatar by descriptor<Unit, Unit>("loadAvatar")
    val saveAudit by descriptor<Unit, Unit>("saveAudit")
    val refreshToken by descriptor<Unit, Unit>("refreshToken")

    init { seal() }
}

private val testMetadata = clientMetadataOf(TestApis) { }

class CoroutinePuzzleHistoryRendererTest {

    @Test
    fun `an answer crossing cancellation confirms the cancelled call finished`() {
        val actual = context(testMetadata) { renderCoroutinePuzzleHistory(listOf(
            CoroutinePuzzleHistoryBatch.Submission(listOf(submitted(1, TestApis.endpoint.id))),
            CoroutinePuzzleHistoryBatch.Submission(listOf(cancellationRequested(1))),
            CoroutinePuzzleHistoryBatch.Expectation(listOf(answered(1))),
        )) }
        assertEquals(
            """
                batch 123
                A     ●×c

                A endpoint

                ● start  ✓ completed  ! throw
                × cancellation requested  c cancellation completed  > still running
            """.trimIndent(), actual,
        )
    }

    @Test
    fun `a cancellation flushed after an answer does not reopen the completed call`() {
        val actual = context(testMetadata) { renderCoroutinePuzzleHistory(listOf(
            CoroutinePuzzleHistoryBatch.Submission(listOf(submitted(1, TestApis.endpoint.id))),
            CoroutinePuzzleHistoryBatch.Expectation(listOf(answered(1))),
            CoroutinePuzzleHistoryBatch.Submission(listOf(cancellationRequested(1))),
            CoroutinePuzzleHistoryBatch.Expectation(listOf(answered(1))),
        )) }
        assertEquals(
            """
                batch 1234
                A     ●✓${" ".repeat(2)}

                A endpoint

                ● start  ✓ completed  ! throw
                × cancellation requested  c cancellation completed  > still running
            """.trimIndent(), actual,
        )
    }

    @Test
    fun `renders concurrent calls, grouped completions, cancellation, and a hung call`() {
        val fetchUser = TestApis.fetchUser.id
        val loadAvatar = TestApis.loadAvatar.id
        val saveAudit = TestApis.saveAudit.id
        val refreshToken = TestApis.refreshToken.id

        val actual = context(testMetadata) { renderCoroutinePuzzleHistory(
            listOf(
                // Batch 1: three calls start concurrently.
                CoroutinePuzzleHistoryBatch.Submission(listOf(
                    submitted(100, fetchUser),
                    submitted(101, fetchUser),
                    submitted(102, loadAvatar),
                )),
                // Batch 2: both fetchUser calls receive their answers together.
                CoroutinePuzzleHistoryBatch.Expectation(listOf(
                    answered(100),
                    answered(101),
                )),
                // Batch 3: three more calls start concurrently.
                CoroutinePuzzleHistoryBatch.Submission(listOf(
                    submitted(103, saveAudit),
                    submitted(104, refreshToken),
                    submitted(105, loadAvatar),
                )),
                // Batch 4: one call answers and one throws.
                CoroutinePuzzleHistoryBatch.Expectation(listOf(
                    answered(102),
                    threw(103),
                )),
                // Batch 5: two calls start and one cancellation is requested.
                CoroutinePuzzleHistoryBatch.Submission(listOf(
                    submitted(106, fetchUser),
                    submitted(107, saveAudit),
                    cancellationRequested(105),
                )),
                // Batch 6: cancellation and another call complete together.
                CoroutinePuzzleHistoryBatch.Expectation(listOf(
                    cancellationCompleted(105),
                    answered(106),
                )),
                // Batch 7: another refreshToken call starts.
                CoroutinePuzzleHistoryBatch.Submission(listOf(
                    submitted(108, refreshToken),
                )),
                // Batch 8: two calls complete together.
                CoroutinePuzzleHistoryBatch.Expectation(listOf(
                    answered(107),
                    answered(108),
                )),
                // Batch 9: one final call starts.
                CoroutinePuzzleHistoryBatch.Submission(listOf(
                    submitted(109, fetchUser),
                )),
                // Batch 10: the final call completes.
                CoroutinePuzzleHistoryBatch.Expectation(listOf(
                    answered(109),
                )),
            ),
        ) }

        val expected = """
                           1
            batch 1234567890
            A     ●✓${" ".repeat(8)}
            A     ●✓${" ".repeat(8)}
            B     ●──✓${" ".repeat(6)}
            C       ●!${" ".repeat(6)}
            D       ●──────>
            B       ●─×c${" ".repeat(4)}
            A         ●✓${" ".repeat(4)}
            C         ●──✓${" ".repeat(2)}
            D           ●✓${" ".repeat(2)}
            A             ●✓

            A fetchUser   B loadAvatar
            C saveAudit   D refreshToken

            ● start  ✓ completed  ! throw
            × cancellation requested  c cancellation completed  > still running
        """.trimIndent()

        assertEquals(expected, actual)
    }

    private fun submitted(
        callId: Long,
        endPoint: CoroutinePuzzleEndPointId,
    ) = WithCallId<CoroutinePuzzleSubmissionPayload>(
        callId = callId,
        payload = CoroutinePuzzleCallPayload.CallSubmitted(endPoint, arg = JsonNull),
    )

    private fun cancellationRequested(callId: Long) = WithCallId<CoroutinePuzzleSubmissionPayload>(
        callId = callId,
        payload = CoroutinePuzzleCallPayload.Cancel,
    )

    private fun answered(callId: Long) = WithCallId<CoroutinePuzzleExpectationPayload>(
        callId = callId,
        payload = Outcome.Returned(JsonNull),
    )

    private fun threw(callId: Long) = WithCallId<CoroutinePuzzleExpectationPayload>(
        callId = callId,
        payload = Outcome.Threw("Boom"),
    )

    private fun cancellationCompleted(callId: Long) = WithCallId(
        callId = callId,
        payload = Outcome.Cancelled,
    )
}
