package kmpworkshop.client

import kmpworkshop.common.CoroutinePuzzleEndPointDescriptor
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.CoroutinePuzzleSolutionScope
import kmpworkshop.common.WorkshopServer
import kmpworkshop.common.callLifetime
import kmpworkshop.common.withImportantCleanup
import kotlinx.coroutines.CoroutineScope

suspend fun <T> checkCoroutinePuzzle(
    workshopServer: WorkshopServer,
    puzzleId: String,
    solution: suspend (T) -> Unit,
    builder: context(CoroutinePuzzleSolutionScope) () -> T,
): CoroutinePuzzleSolutionResult = checkCoroutinePuzzleInternal(workshopServer, puzzleId) { solution(builder()) }

suspend fun checkCoroutinePuzzleInternal(
    workshopServer: WorkshopServer,
    puzzleId: String,
    solution: suspend context(CoroutinePuzzleSolutionScope) CoroutineScope.() -> Unit,
): CoroutinePuzzleSolutionResult = withImportantCleanup {
    workshopServer.doCoroutinePuzzleSolveAttempt(puzzleId) {
        solution()
    }
}

/**
 * Endpoints whose calls are internal scaffolding, not something the user themselves called - showing them in the
 * history of a failed solve attempt would only confuse the user, since they never called it directly.
 */
private val endpointsHiddenFromHistory: Set<CoroutinePuzzleEndPointDescriptor> = setOf(callLifetime.descriptor)

fun CoroutinePuzzleEndPointDescriptor.isHiddenFromHistory(): Boolean = this in endpointsHiddenFromHistory

fun CoroutinePuzzleSolutionResult.Failure.toMessage(): String = """
    |${reason.toMessage()}
    |
    |The history of actions was:
    ${
        history
            .filterNot { it.isHiddenFromHistory() }
            .joinToString("\n") { "| - ${it.description}" }
    }
""".trimMargin()

fun CoroutinePuzzleSolutionResult.Failure.Reason.toMessage(): String = when (this) {
    is CoroutinePuzzleSolutionResult.Failure.Reason.ExactParallelismMismatch -> """
        |You tried to call these at the same time:
        |${formatCallAttemptsWithMargins(submissions.map { it.description }.distinct())}
        |However, you were expected to call exactly these
    """.trimIndent()
    is CoroutinePuzzleSolutionResult.Failure.Reason.MoreExpectationsThanSubmissions -> """
        |You made too few function calls. We're still expecting ${
            expectedFollowups.map { it.description }.distinct().let { expectedCalls ->
                expectedCalls.singleOrNull()
                    ?: """
                        either:
                        |${expectedCalls.joinToString(",\n| or ", postfix = "\n|")}
                    """.trimIndent()
            }
        }
    """.trimMargin()
    is CoroutinePuzzleSolutionResult.Failure.Reason.MoreSubmissionsThanExpectations -> """
        |Attempted to call ${formatCallAttemptsWithMargins(overshotSubmissions.map { it.description }.distinct())}
    """.trimMargin()
    is CoroutinePuzzleSolutionResult.Failure.Reason.UnexpectedSubmissions -> """
        |Currently the expected ${
            expectations.map { it.description }.distinct().let { expectedCalls ->
                expectedCalls.singleOrNull()?.let { "action is: $it" }
                    ?: """
                        actions are either:
                        |${expectedCalls.joinToString(",\n| or ")}
                    """.trimIndent()
            }
        }
        |But instead you attempted to call ${
            formatCallAttemptsWithMargins(unexpectedSubmissions.map { it.description }.distinct())
        }
    """.trimMargin()
    is CoroutinePuzzleSolutionResult.Failure.Reason.Custom -> message
}

private fun formatCallAttemptsWithMargins(attempts: List<String>): String =
    attempts.singleOrNull() ?: attempts.joinToString(", and", prefix = "all of these at the same time: \n") {
        "|    $it\n"
    }