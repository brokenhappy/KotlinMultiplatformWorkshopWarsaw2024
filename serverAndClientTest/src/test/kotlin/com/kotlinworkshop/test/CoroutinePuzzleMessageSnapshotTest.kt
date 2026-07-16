package com.kotlinworkshop.test

import kmpworkshop.client.toMessage
import kmpworkshop.common.CoroutinePuzzleEndPointDescriptor
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.CoroutinePuzzleSolutionResult.Failure.Reason
import kmpworkshop.common.callLifetime
import org.junit.jupiter.api.Test

private val foo = CoroutinePuzzleEndPointDescriptor("call foo()")
private val bar = CoroutinePuzzleEndPointDescriptor("call bar()")
private val baz = CoroutinePuzzleEndPointDescriptor("call baz()")
private val secret = callLifetime.descriptor

/**
 * These pin down the exact rendered output of toMessage() as a regression net, via file-based snapshots (see
 * SnapshotTesting.kt). Each test passes its own explicit, literal snapshot path - if one of these needs to change,
 * that's a deliberate formatting change, not an accident - review the snapshot file diff (e.g. via IntelliJ's
 * "Accept" action on the failing assertion) before updating it.
 */
class CoroutinePuzzleMessageSnapshotTest {
    @Test
    fun `ExactParallelismMismatch with a single submission`() {
        Reason.ExactParallelismMismatch(
            submissions = listOf(foo),
            expectations = listOf(foo, bar),
        )
            .toMessage()
            .assertMatchesSnapshot(
                "snapshots/CoroutinePuzzleMessageSnapshotTest/ExactParallelismMismatch_with_a_single_submission.txt",
            )
    }

    @Test
    fun `ExactParallelismMismatch with multiple concurrent submissions`() {
        Reason.ExactParallelismMismatch(
            submissions = listOf(foo, bar),
            expectations = listOf(foo, bar, baz),
        )
            .toMessage()
            .assertMatchesSnapshot(
                "snapshots/CoroutinePuzzleMessageSnapshotTest/ExactParallelismMismatch_with_multiple_concurrent_submissions.txt",
            )
    }

    @Test
    fun `ExactParallelismMismatch with no submissions`() {
        // Covers the "nothing" branch of the call-list formatting, which a real puzzle failure wouldn't hit for
        // submissions (there's always at least one, or this Reason wouldn't have fired) but is worth pinning down
        // since it's a distinct branch of the rendering logic.
        Reason.ExactParallelismMismatch(
            submissions = emptyList(),
            expectations = listOf(foo),
        )
            .toMessage()
            .assertMatchesSnapshot(
                "snapshots/CoroutinePuzzleMessageSnapshotTest/ExactParallelismMismatch_with_no_submissions.txt",
            )
    }

    @Test
    fun `MoreExpectationsThanSubmissions with a single expected call`() {
        Reason.MoreExpectationsThanSubmissions(expectedFollowups = listOf(foo))
            .toMessage()
            .assertMatchesSnapshot(
                "snapshots/CoroutinePuzzleMessageSnapshotTest/MoreExpectationsThanSubmissions_with_a_single_expected_call.txt",
            )
    }

    @Test
    fun `MoreExpectationsThanSubmissions with multiple expected calls`() {
        Reason.MoreExpectationsThanSubmissions(expectedFollowups = listOf(foo, bar))
            .toMessage()
            .assertMatchesSnapshot(
                "snapshots/CoroutinePuzzleMessageSnapshotTest/MoreExpectationsThanSubmissions_with_multiple_expected_calls.txt",
            )
    }

    @Test
    fun `MoreExpectationsThanSubmissions with no expected calls`() {
        Reason.MoreExpectationsThanSubmissions(expectedFollowups = emptyList())
            .toMessage()
            .assertMatchesSnapshot(
                "snapshots/CoroutinePuzzleMessageSnapshotTest/MoreExpectationsThanSubmissions_with_no_expected_calls.txt",
            )
    }

    @Test
    fun `MoreSubmissionsThanExpectations with a single overshot submission`() {
        Reason.MoreSubmissionsThanExpectations(overshotSubmissions = listOf(foo))
            .toMessage()
            .assertMatchesSnapshot(
                "snapshots/CoroutinePuzzleMessageSnapshotTest/MoreSubmissionsThanExpectations_with_a_single_overshot_submission.txt",
            )
    }

    @Test
    fun `MoreSubmissionsThanExpectations with multiple overshot submissions`() {
        Reason.MoreSubmissionsThanExpectations(overshotSubmissions = listOf(foo, bar))
            .toMessage()
            .assertMatchesSnapshot(
                "snapshots/CoroutinePuzzleMessageSnapshotTest/MoreSubmissionsThanExpectations_with_multiple_overshot_submissions.txt",
            )
    }

    @Test
    fun `MoreSubmissionsThanExpectations with no overshot submissions`() {
        // Covers the "nothing" branch of the call-list formatting; a real puzzle failure wouldn't reach this
        // Reason with an empty list, but it's a distinct branch of the rendering logic worth pinning down.
        Reason.MoreSubmissionsThanExpectations(overshotSubmissions = emptyList())
            .toMessage()
            .assertMatchesSnapshot(
                "snapshots/CoroutinePuzzleMessageSnapshotTest/MoreSubmissionsThanExpectations_with_no_overshot_submissions.txt",
            )
    }

    @Test
    fun `UnexpectedSubmissions with a single expectation and a single unexpected submission`() {
        Reason.UnexpectedSubmissions(
            unexpectedSubmissions = listOf(bar),
            expectations = listOf(foo),
        )
            .toMessage()
            .assertMatchesSnapshot(
                "snapshots/CoroutinePuzzleMessageSnapshotTest/UnexpectedSubmissions_with_a_single_expectation_and_a_single_unexpected_submission.txt",
            )
    }

    @Test
    fun `UnexpectedSubmissions with multiple expectations and multiple unexpected submissions`() {
        Reason.UnexpectedSubmissions(
            unexpectedSubmissions = listOf(baz, foo),
            expectations = listOf(foo, bar),
        ).toMessage().assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleMessageSnapshotTest/UnexpectedSubmissions_with_multiple_expectations_and_multiple_unexpected_submissions.txt",
        )
    }

    @Test
    fun `UnexpectedSubmissions with no expectations and no unexpected submissions`() {
        // Covers the "nothing" branch of both the alternatives formatting (expectations) and the call-list
        // formatting (unexpectedSubmissions), plus the "actions are" (rather than "action is") text for a
        // zero-sized expectations list.
        Reason.UnexpectedSubmissions(
            unexpectedSubmissions = emptyList(),
            expectations = emptyList(),
        )
            .toMessage()
            .assertMatchesSnapshot(
                "snapshots/CoroutinePuzzleMessageSnapshotTest/UnexpectedSubmissions_with_no_expectations_and_no_unexpected_submissions.txt",
            )
    }

    @Test
    fun `Custom just returns its message`() {
        Reason.Custom("You submitted 3, but the oldest user is 47.")
            .toMessage()
            .assertMatchesSnapshot("snapshots/CoroutinePuzzleMessageSnapshotTest/Custom_just_returns_its_message.txt")
    }

    @Test
    fun `Failure toMessage combines the reason with the history`() {
        CoroutinePuzzleSolutionResult.Failure(
            history = listOf(foo, bar),
            reason = Reason.Custom("You submitted 3, but the oldest user is 47."),
        )
            .toMessage()
            .assertMatchesSnapshot(
                "snapshots/CoroutinePuzzleMessageSnapshotTest/Failure_toMessage_combines_the_reason_with_the_history.txt",
            )
    }

    @Test
    fun `Failure toMessage filters isHiddenInHistory endpoints out of the history`() {
        CoroutinePuzzleSolutionResult.Failure(
            history = listOf(foo, secret, bar),
            reason = Reason.Custom("You submitted 3, but the oldest user is 47."),
        )
            .toMessage()
            .assertMatchesSnapshot(
                "snapshots/CoroutinePuzzleMessageSnapshotTest/Failure_toMessage_filters_isHiddenInHistory_endpoints_out_of_the_history.txt",
            )
    }

    @Test
    fun `Failure toMessage with an empty history`() {
        CoroutinePuzzleSolutionResult.Failure(
            history = emptyList(),
            reason = Reason.Custom("You submitted 3, but the oldest user is 47."),
        )
            .toMessage()
            .assertMatchesSnapshot(
                "snapshots/CoroutinePuzzleMessageSnapshotTest/Failure_toMessage_with_an_empty_history.txt",
            )
    }
}
