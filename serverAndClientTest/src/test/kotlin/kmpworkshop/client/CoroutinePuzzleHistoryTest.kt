@file:Suppress("SameParameterValue")

package kmpworkshop.client

import kmpworkshop.common.CoroutinePuzzleBatchEntry
import kmpworkshop.common.CoroutinePuzzleBatchEntry.ExpectationPayload
import kmpworkshop.common.CoroutinePuzzleBatchEntry.SubmissionPayload
import kmpworkshop.common.CoroutinePuzzleEndPointDescriptor
import kmpworkshop.common.CoroutinePuzzleHistoryBatch
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.Test

class CoroutinePuzzleHistoryRendererTest {

    @Test
    fun `renders an answer returned after cancellation was requested`() {
        val endpoint = CoroutinePuzzleEndPointDescriptor("endpoint")

        val actual = renderCoroutinePuzzleHistory(listOf(
            CoroutinePuzzleHistoryBatch.Submission(listOf(submitted(1, endpoint))),
            CoroutinePuzzleHistoryBatch.Submission(listOf(cancellationRequested(1))),
            CoroutinePuzzleHistoryBatch.Expectation(listOf(answered(1))),
        ))

        assertEquals(
            """
                batch 123
                A     ●×✓

                A endpoint

                ● start  ✓ answer  ! throw
                × cancellation requested  c cancellation completed  > still running
            """.trimIndent(),
            actual,
        )
    }

    @Test
    fun `renders a cancellation request flushed after an answer`() {
        val endpoint = CoroutinePuzzleEndPointDescriptor("endpoint")

        val actual = renderCoroutinePuzzleHistory(listOf(
            CoroutinePuzzleHistoryBatch.Submission(listOf(submitted(1, endpoint))),
            CoroutinePuzzleHistoryBatch.Expectation(listOf(answered(1))),
            CoroutinePuzzleHistoryBatch.Submission(listOf(cancellationRequested(1))),
        ))

        assertEquals(
            """
                batch 123
                A     ●✓×

                A endpoint

                ● start  ✓ answer  ! throw
                × cancellation requested  c cancellation completed  > still running
            """.trimIndent(),
            actual,
        )
    }

    @Test
    fun `renders concurrent calls, grouped completions, cancellation, and a hung call`() {
        val fetchUser = CoroutinePuzzleEndPointDescriptor("fetchUser")
        val loadAvatar = CoroutinePuzzleEndPointDescriptor("loadAvatar")
        val saveAudit = CoroutinePuzzleEndPointDescriptor("saveAudit")
        val refreshToken = CoroutinePuzzleEndPointDescriptor("refreshToken")

        val actual = renderCoroutinePuzzleHistory(
            listOf(
                // Batch 1: three calls start concurrently.
                CoroutinePuzzleHistoryBatch.Submission(listOf(
                    submitted(100, fetchUser),
                    submitted(101, fetchUser),
                    submitted(102, loadAvatar),
                )),
                // Batch 2: both fetchUser calls complete together.
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
        )

        val expected = """
                           1
            batch 1234567890
            A     ●✓        
            A     ●✓        
            B     ●──✓      
            C       ●!      
            D       ●──────>
            B       ●─×c    
            A         ●✓    
            C         ●──✓  
            D           ●✓  
            A             ●✓

            A fetchUser   B loadAvatar
            C saveAudit   D refreshToken

            ● start  ✓ answer  ! throw
            × cancellation requested  c cancellation completed  > still running
        """.trimIndent()

        assertEquals(expected, actual)
    }

    private fun submitted(
        callId: Long,
        endPoint: CoroutinePuzzleEndPointDescriptor,
    ) = CoroutinePuzzleBatchEntry<SubmissionPayload>(
        callId = callId,
        payload = SubmissionPayload.CallSubmitted(endPoint, arg = JsonNull),
    )

    private fun cancellationRequested(callId: Long) = CoroutinePuzzleBatchEntry<SubmissionPayload>(
        callId = callId,
        payload = SubmissionPayload.CallShouldCancel,
    )

    private fun answered(callId: Long) = CoroutinePuzzleBatchEntry<ExpectationPayload>(
        callId = callId,
        payload = ExpectationPayload.CallAnswered(JsonNull),
    )

    private fun threw(callId: Long) = CoroutinePuzzleBatchEntry<ExpectationPayload>(
        callId = callId,
        payload = ExpectationPayload.CallThrew,
    )

    private fun cancellationCompleted(callId: Long) = CoroutinePuzzleBatchEntry<ExpectationPayload>(
        callId = callId,
        payload = ExpectationPayload.CallCancellationCompleted,
    )
}
