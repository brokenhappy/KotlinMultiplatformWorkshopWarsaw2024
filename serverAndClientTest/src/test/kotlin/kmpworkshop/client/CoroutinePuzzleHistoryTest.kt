@file:Suppress("SameParameterValue")

package kmpworkshop.client

import kmpworkshop.common.WithCallId
import kmpworkshop.common.CoroutinePuzzleExpectationPayload
import kmpworkshop.common.CoroutinePuzzleSubmissionPayload
import kmpworkshop.common.CoroutinePuzzleEndPointId
import kmpworkshop.common.CoroutinePuzzleHistoryBatch
import kmpworkshop.common.EndpointDescriptorRegistry
import kmpworkshop.common.descriptor
import kmpworkshop.client.clientMetadataOf
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
    fun `renders an answer returned after cancellation was requested`() {
        val endpoint = TestApis.endpoint.id

        val actual = context(testMetadata) {
            renderCoroutinePuzzleHistory(listOf(
                CoroutinePuzzleHistoryBatch.Submission(listOf(submitted(1, endpoint))),
                CoroutinePuzzleHistoryBatch.Submission(listOf(cancellationRequested(1))),
                CoroutinePuzzleHistoryBatch.Expectation(listOf(answered(1))),
            ))
        }

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
        val endpoint = TestApis.endpoint.id

        val actual = context(testMetadata) { renderCoroutinePuzzleHistory(listOf(
            CoroutinePuzzleHistoryBatch.Submission(listOf(submitted(1, endpoint))),
            CoroutinePuzzleHistoryBatch.Expectation(listOf(answered(1))),
            CoroutinePuzzleHistoryBatch.Submission(listOf(cancellationRequested(1))),
        )) }

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
        ) }

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
        endPoint: CoroutinePuzzleEndPointId,
    ) = WithCallId<CoroutinePuzzleSubmissionPayload>(
        callId = callId,
        payload = CoroutinePuzzleSubmissionPayload.CallSubmitted(endPoint, arg = JsonNull),
    )

    private fun cancellationRequested(callId: Long) = WithCallId<CoroutinePuzzleSubmissionPayload>(
        callId = callId,
        payload = CoroutinePuzzleSubmissionPayload.CallShouldCancel,
    )

    private fun answered(callId: Long) = WithCallId<CoroutinePuzzleExpectationPayload>(
        callId = callId,
        payload = CoroutinePuzzleExpectationPayload.CallAnswered(JsonNull),
    )

    private fun threw(callId: Long) = WithCallId<CoroutinePuzzleExpectationPayload>(
        callId = callId,
        payload = CoroutinePuzzleExpectationPayload.CallThrew("Boom"),
    )

    private fun cancellationCompleted(callId: Long) = WithCallId<CoroutinePuzzleExpectationPayload>(
        callId = callId,
        payload = CoroutinePuzzleExpectationPayload.CallCancellationCompleted,
    )
}
