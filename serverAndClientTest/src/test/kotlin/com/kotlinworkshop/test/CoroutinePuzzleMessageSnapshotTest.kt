package com.kotlinworkshop.test

import kmpworkshop.client.toMessage
import kmpworkshop.common.CoroutinePuzzleEndPointDescriptor
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.CoroutinePuzzleSolutionResult.Failure.Reason
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

private val foo = CoroutinePuzzleEndPointDescriptor("call foo()")
private val bar = CoroutinePuzzleEndPointDescriptor("call bar()")
private val baz = CoroutinePuzzleEndPointDescriptor("call baz()")
private val secret = CoroutinePuzzleEndPointDescriptor("call secret()", isHiddenInHistory = true)

/**
 * These pin down the exact (occasionally quirky, e.g. inconsistent margin trimming) rendered output of
 * toMessage() as a regression net. If one of these needs to change, that's a deliberate formatting change,
 * not an accident.
 */
class CoroutinePuzzleMessageSnapshotTest {
    @Test
    fun `ExactParallelismMismatch with a single submission`() {
        assertEquals(
            "|You tried to call these at the same time:\n" +
                "|call foo()\n" +
                "|However, you were expected to call exactly these",
            Reason.ExactParallelismMismatch(
                submissions = listOf(foo),
                expectations = listOf(foo, bar),
            ).toMessage(),
        )
    }

    @Test
    fun `ExactParallelismMismatch with multiple concurrent submissions`() {
        assertEquals(
            "        |You tried to call these at the same time:\n" +
                "        |all of these at the same time: \n" +
                "|    call foo()\n" +
                ", and|    call bar()\n" +
                "\n" +
                "        |However, you were expected to call exactly these",
            Reason.ExactParallelismMismatch(
                submissions = listOf(foo, bar),
                expectations = listOf(foo, bar, baz),
            ).toMessage(),
        )
    }

    @Test
    fun `MoreExpectationsThanSubmissions with a single expected call`() {
        assertEquals(
            "You made too few function calls. We're still expecting call foo()",
            Reason.MoreExpectationsThanSubmissions(expectedFollowups = listOf(foo)).toMessage(),
        )
    }

    @Test
    fun `MoreExpectationsThanSubmissions with multiple expected calls`() {
        assertEquals(
            "You made too few function calls. We're still expecting                         either:\n" +
                "call foo(),\n" +
                " or call bar()\n",
            Reason.MoreExpectationsThanSubmissions(expectedFollowups = listOf(foo, bar)).toMessage(),
        )
    }

    @Test
    fun `MoreSubmissionsThanExpectations with a single overshot submission`() {
        assertEquals(
            "Attempted to call call foo()",
            Reason.MoreSubmissionsThanExpectations(overshotSubmissions = listOf(foo)).toMessage(),
        )
    }

    @Test
    fun `MoreSubmissionsThanExpectations with multiple overshot submissions`() {
        assertEquals(
            "Attempted to call all of these at the same time: \n" +
                "    call foo()\n" +
                ", and|    call bar()\n",
            Reason.MoreSubmissionsThanExpectations(overshotSubmissions = listOf(foo, bar)).toMessage(),
        )
    }

    @Test
    fun `UnexpectedSubmissions with a single expectation and a single unexpected submission`() {
        assertEquals(
            "Currently the expected action is: call foo()\n" +
                "But instead you attempted to call call bar()",
            Reason.UnexpectedSubmissions(
                unexpectedSubmissions = listOf(bar),
                expectations = listOf(foo),
            ).toMessage(),
        )
    }

    @Test
    fun `UnexpectedSubmissions with multiple expectations and multiple unexpected submissions`() {
        assertEquals(
            "Currently the expected                         actions are either:\n" +
                "call foo(),\n" +
                " or call bar()\n" +
                "But instead you attempted to call all of these at the same time: \n" +
                "    call baz()\n" +
                ", and|    call foo()\n",
            Reason.UnexpectedSubmissions(
                unexpectedSubmissions = listOf(baz, foo),
                expectations = listOf(foo, bar),
            ).toMessage(),
        )
    }

    @Test
    fun `Custom just returns its message`() {
        assertEquals(
            "You submitted 3, but the oldest user is 47.",
            Reason.Custom("You submitted 3, but the oldest user is 47.").toMessage(),
        )
    }

    @Test
    fun `Failure toMessage combines the reason with the history`() {
        assertEquals(
            "You submitted 3, but the oldest user is 47.\n" +
                "\n" +
                "The history of actions was:\n" +
                " - call foo()\n" +
                " - call bar()",
            CoroutinePuzzleSolutionResult.Failure(
                history = listOf(foo, bar),
                reason = Reason.Custom("You submitted 3, but the oldest user is 47."),
            ).toMessage(),
        )
    }

    @Test
    fun `Failure toMessage filters isHiddenInHistory endpoints out of the history`() {
        assertEquals(
            "You submitted 3, but the oldest user is 47.\n" +
                "\n" +
                "The history of actions was:\n" +
                " - call foo()\n" +
                " - call bar()",
            CoroutinePuzzleSolutionResult.Failure(
                history = listOf(foo, secret, bar),
                reason = Reason.Custom("You submitted 3, but the oldest user is 47."),
            ).toMessage(),
        )
    }

    @Test
    fun `Failure toMessage with an empty history`() {
        assertEquals(
            "You submitted 3, but the oldest user is 47.\n" +
                "\n" +
                "The history of actions was:\n" +
                "    ",
            CoroutinePuzzleSolutionResult.Failure(
                history = emptyList(),
                reason = Reason.Custom("You submitted 3, but the oldest user is 47."),
            ).toMessage(),
        )
    }
}
