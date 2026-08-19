package com.kotlinworkshop.test

import kmpworkshop.client.toMessage
import kmpworkshop.client.clientMetadataOf
import kmpworkshop.common.CoroutinePuzzleEndPointId
import kmpworkshop.common.CoroutinePuzzleExpectedFollowup
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.EndpointDescriptorRegistry
import kmpworkshop.common.descriptor
import org.junit.jupiter.api.Test

private object MessageTestApis : EndpointDescriptorRegistry() {
    val foo by descriptor<Unit, Unit>("call foo()")
    val bar by descriptor<Unit, Unit>("call bar()")
    val baz by descriptor<Unit, Unit>("call baz()")

    init { seal() }
}

private val testMetadata = clientMetadataOf(MessageTestApis) { }
private val foo = MessageTestApis.foo.id
private val bar = MessageTestApis.bar.id
private val baz = MessageTestApis.baz.id

private fun CoroutinePuzzleSolutionResult.renderClientMessage(): String =
    context(testMetadata) { toMessage() }

class CoroutinePuzzleMessageSnapshotTest {
    @Test
    fun `ExactParallelismMismatch with a single submission`() {
        CoroutinePuzzleSolutionResult.ExactParallelismMismatchFailure(
            submissions = listOf(foo),
            expectations = listOf(CoroutinePuzzleExpectedFollowup(foo), CoroutinePuzzleExpectedFollowup(bar)),
        ).renderClientMessage().assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleMessageSnapshotTest/ExactParallelismMismatch_with_a_single_submission.txt",
        )
    }

    @Test
    fun `ExactParallelismMismatch with multiple concurrent submissions`() {
        CoroutinePuzzleSolutionResult.ExactParallelismMismatchFailure(
            submissions = listOf(foo, bar),
            expectations = listOf(
                CoroutinePuzzleExpectedFollowup(foo),
                CoroutinePuzzleExpectedFollowup(bar),
                CoroutinePuzzleExpectedFollowup(baz),
            ),
        ).renderClientMessage().assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleMessageSnapshotTest/ExactParallelismMismatch_with_multiple_concurrent_submissions.txt",
        )
    }

    @Test
    fun `ExactParallelismMismatch with no submissions`() {
        // Covers the "nothing" branch of the call-list formatting, which a real puzzle failure wouldn't hit for
        // submissions (there's always at least one, or this Reason wouldn't have fired) but is worth pinning down
        // since it's a distinct branch of the rendering logic.
        CoroutinePuzzleSolutionResult.ExactParallelismMismatchFailure(
            submissions = emptyList(),
            expectations = listOf(CoroutinePuzzleExpectedFollowup(foo)),
        ).renderClientMessage().assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleMessageSnapshotTest/ExactParallelismMismatch_with_no_submissions.txt",
        )
    }

    @Test
    fun `MoreExpectationsThanSubmissions with a single expected call`() {
        CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure(expectedFollowups = listOf(CoroutinePuzzleExpectedFollowup(foo)))
            .renderClientMessage()
            .assertMatchesSnapshot(
                "snapshots/CoroutinePuzzleMessageSnapshotTest/MoreExpectationsThanSubmissions_with_a_single_expected_call.txt",
            )
    }

    @Test
    fun `MoreExpectationsThanSubmissions with multiple expected calls`() {
        CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure(
            expectedFollowups = listOf(CoroutinePuzzleExpectedFollowup(foo), CoroutinePuzzleExpectedFollowup(bar)),
        )
            .renderClientMessage()
            .assertMatchesSnapshot(
                "snapshots/CoroutinePuzzleMessageSnapshotTest/MoreExpectationsThanSubmissions_with_multiple_expected_calls.txt",
            )
    }

    @Test
    fun `MoreExpectationsThanSubmissions with no expected calls`() {
        CoroutinePuzzleSolutionResult.MoreExpectationsThanSubmissionsFailure(expectedFollowups = emptyList())
            .renderClientMessage()
            .assertMatchesSnapshot(
                "snapshots/CoroutinePuzzleMessageSnapshotTest/MoreExpectationsThanSubmissions_with_no_expected_calls.txt",
            )
    }

    @Test
    fun `MoreSubmissionsThanExpectations with a single overshot submission`() {
        CoroutinePuzzleSolutionResult.MoreSubmissionsThanExpectationsFailure(overshotSubmissions = listOf(foo))
            .renderClientMessage()
            .assertMatchesSnapshot(
                "snapshots/CoroutinePuzzleMessageSnapshotTest/MoreSubmissionsThanExpectations_with_a_single_overshot_submission.txt",
            )
    }

    @Test
    fun `MoreSubmissionsThanExpectations with multiple overshot submissions`() {
        CoroutinePuzzleSolutionResult.MoreSubmissionsThanExpectationsFailure(overshotSubmissions = listOf(foo, bar))
            .renderClientMessage()
            .assertMatchesSnapshot(
                "snapshots/CoroutinePuzzleMessageSnapshotTest/MoreSubmissionsThanExpectations_with_multiple_overshot_submissions.txt",
            )
    }

    @Test
    fun `MoreSubmissionsThanExpectations with no overshot submissions`() {
        // Covers the "nothing" branch of the call-list formatting; a real puzzle failure wouldn't reach this
        // Reason with an empty list, but it's a distinct branch of the rendering logic worth pinning down.
        CoroutinePuzzleSolutionResult.MoreSubmissionsThanExpectationsFailure(overshotSubmissions = emptyList())
            .renderClientMessage()
            .assertMatchesSnapshot(
                "snapshots/CoroutinePuzzleMessageSnapshotTest/MoreSubmissionsThanExpectations_with_no_overshot_submissions.txt",
            )
    }

    @Test
    fun `UnexpectedSubmissions with a single expectation and a single unexpected submission`() {
        CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure(
            unexpectedSubmissions = listOf(bar),
            expectations = listOf(CoroutinePuzzleExpectedFollowup(foo)),
        ).renderClientMessage().assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleMessageSnapshotTest/UnexpectedSubmissions_with_a_single_expectation_and_a_single_unexpected_submission.txt",
        )
    }

    @Test
    fun `UnexpectedSubmissions with multiple expectations and multiple unexpected submissions`() {
        CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure(
            unexpectedSubmissions = listOf(baz, foo),
            expectations = listOf(CoroutinePuzzleExpectedFollowup(foo), CoroutinePuzzleExpectedFollowup(bar)),
        ).renderClientMessage().assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleMessageSnapshotTest/UnexpectedSubmissions_with_multiple_expectations_and_multiple_unexpected_submissions.txt",
        )
    }

    @Test
    fun `UnexpectedSubmissions with no expectations and no unexpected submissions`() {
        CoroutinePuzzleSolutionResult.UnexpectedSubmissionsFailure(
            unexpectedSubmissions = emptyList(),
            expectations = emptyList(),
        ).renderClientMessage().assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleMessageSnapshotTest/UnexpectedSubmissions_with_no_expectations_and_no_unexpected_submissions.txt",
        )
    }

    @Test
    fun `Custom just returns its message`() {
        CoroutinePuzzleSolutionResult.CustomFailure("You submitted 3, but the oldest user is 47.")
            .renderClientMessage()
            .assertMatchesSnapshot("snapshots/CoroutinePuzzleMessageSnapshotTest/Custom_just_returns_its_message.txt")
    }
}
