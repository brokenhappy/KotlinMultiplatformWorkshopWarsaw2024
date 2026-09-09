package kmpworkshop.server

import kmpworkshop.api.ChatMessage
import kmpworkshop.api.TypingStatus
import kmpworkshop.common.CoroutinePuzzleEndPoint
import kmpworkshop.common.UnmatchedSubmission
import kmpworkshop.common.DefaultApis.appendChatMessageToTranscript
import kmpworkshop.common.DefaultApis.chatMessageAccepted
import kmpworkshop.common.DefaultApis.chatTypingStatusAccepted
import kmpworkshop.common.DefaultApis.emitIncomingChatMessage
import kmpworkshop.common.DefaultApis.emitSentChatMessage
import kmpworkshop.common.DefaultApis.emitTypingStatus
import kmpworkshop.common.DefaultApis.updateCurrentTypingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private val firstIncomingMessage = ChatMessage("Ada", "Is anyone there?")
private val secondIncomingMessage = ChatMessage("Ada", "I found the channel.")
private val sentMessage = ChatMessage("You", "Yes — I can hear you.")

fun chatMessagesRendezvousPuzzle() = chatChannelsPuzzle { emitIncoming, emitSent, _ ->
    evaluateSerializedTranscriptWrites(emitIncoming, emitSent)
}

fun chatMessagesBufferedPuzzle() = chatChannelsPuzzle { emitIncoming, emitSent, _ ->
    evaluateBufferedTranscriptWrites(emitIncoming, emitSent)
    null
}

fun chatTypingStatusDropOldestPuzzle() = chatChannelsPuzzle { emitIncoming, emitSent, emitTypingStatus ->
    evaluateBufferedTranscriptWrites(emitIncoming, emitSent)
    evaluateDropOldestTypingStatus(emitTypingStatus)
    null
}

private fun chatChannelsPuzzle(
    evaluate: suspend context(CoroutinePuzzleBuilderScope) (
        emitIncoming: suspend (ChatMessage) -> Unit,
        emitSent: suspend (ChatMessage) -> Unit,
        emitTypingStatus: suspend (TypingStatus) -> Unit,
    ) -> String?,
) = coroutinePuzzle {
    val failureAfterFlowsComplete = emitIncomingChatMessage.expectingFlowCollector().use { incomingCollectors ->
        emitSentChatMessage.expectingFlowCollector().use { sentCollectors ->
            emitTypingStatus.expectingFlowCollector().use { typingStatusCollectors ->
                incomingCollectors.use { (_, emitIncoming) ->
                    sentCollectors.use { (_, emitSent) ->
                        typingStatusCollectors.use { (_, emitTypingStatus) ->
                            evaluate(emitIncoming, emitSent, emitTypingStatus)
                        }
                    }
                }
            }
        }
    }
    failureAfterFlowsComplete?.let { fail(it) }
}

context(_: CoroutinePuzzleBuilderScope)
private suspend fun evaluateSerializedTranscriptWrites(
    emitIncoming: suspend (ChatMessage) -> Unit,
    emitSent: suspend (ChatMessage) -> Unit,
) = coroutineScope {
    val releaseFirstWrite = CompletableDeferred<Unit>()
    val firstWriteStarted = CompletableDeferred<Unit>()
    val acceptedMessages = mutableListOf<ChatMessage>()
    val firstWrite = launch {
        appendChatMessageToTranscript.expectCall { actual ->
            verify(actual == firstIncomingMessage) {
                CoroutinePuzzleErrorMessages.wrongChatMessage(actual, firstIncomingMessage)
            }
            firstWriteStarted.complete(Unit)
            releaseFirstWrite.await()
        }
    }

    val incomingEmission = launch { emitIncoming(firstIncomingMessage) }
    firstWriteStarted.await()
    val sentEmission = launch { emitSent(sentMessage) }

    val submissionsWhileWriting = awaitChatCalls()
    val concurrentTranscriptWrite = submissionsWhileWriting.any { it.endPoint == appendChatMessageToTranscript }
    verifyOnlyAcceptanceCalls(
        submissionsWhileWriting.filterNot { it.endPoint == appendChatMessageToTranscript },
        chatMessageAccepted,
    )
    acceptedMessages += consumeMessageAcceptances(submissionsWhileWriting)

    releaseFirstWrite.complete(Unit)
    firstWrite.join()
    acceptedMessages += drainSerializedTranscript(listOf(sentMessage))
    acceptedMessages += consumeRemainingMessageAcceptances()
    incomingEmission.join()
    sentEmission.join()
    verifySameValues(acceptedMessages, listOf(firstIncomingMessage, sentMessage)) {
        CoroutinePuzzleErrorMessages.wrongChatMessage(
            actual = acceptedMessages.firstOrNull() ?: firstIncomingMessage,
            expected = listOf(firstIncomingMessage, sentMessage).first(),
        )
    }
    if (concurrentTranscriptWrite) CoroutinePuzzleErrorMessages.chatTranscriptCallsMustBeSerialized() else null
}

context(_: CoroutinePuzzleBuilderScope)
private suspend fun evaluateBufferedTranscriptWrites(
    emitIncoming: suspend (ChatMessage) -> Unit,
    emitSent: suspend (ChatMessage) -> Unit,
) = coroutineScope {
    val releaseFirstWrite = CompletableDeferred<Unit>()
    val firstWriteStarted = CompletableDeferred<Unit>()
    val firstWrite = launch {
        appendChatMessageToTranscript.expectCall { actual ->
            verify(actual == firstIncomingMessage) {
                CoroutinePuzzleErrorMessages.wrongChatMessage(actual, firstIncomingMessage)
            }
            firstWriteStarted.complete(Unit)
            releaseFirstWrite.await()
        }
    }

    val firstEmission = launch { emitIncoming(firstIncomingMessage) }
    firstWriteStarted.await()

    val firstAcceptance = awaitChatCalls()
    verifyMessageAcceptances(firstAcceptance, expectedCount = 1) {
        CoroutinePuzzleErrorMessages.chatMessageCollectorsMustNotWaitForTranscript()
    }
    val acceptedMessages = consumeMessageAcceptances(firstAcceptance).toMutableList()
    firstEmission.join()

    val secondEmission = launch { emitIncoming(secondIncomingMessage) }
    val sentEmission = launch { emitSent(sentMessage) }
    val bufferedAcceptances = awaitChatCalls()
    verifyMessageAcceptances(bufferedAcceptances, expectedCount = 2) {
        CoroutinePuzzleErrorMessages.chatMessageCollectorsMustNotWaitForTranscript()
    }
    acceptedMessages += consumeMessageAcceptances(bufferedAcceptances)
    secondEmission.join()
    sentEmission.join()
    verifySameValues(
        acceptedMessages,
        listOf(firstIncomingMessage, secondIncomingMessage, sentMessage),
    ) {
        CoroutinePuzzleErrorMessages.chatMessageCollectorsMustNotWaitForTranscript()
    }

    releaseFirstWrite.complete(Unit)
    firstWrite.join()
    drainSerializedTranscript(listOf(secondIncomingMessage, sentMessage))
}

context(_: CoroutinePuzzleBuilderScope)
private suspend fun drainSerializedTranscript(expectedMessages: List<ChatMessage>): List<ChatMessage> {
    val acceptedMessages = mutableListOf<ChatMessage>()
    val appendedMessages = mutableListOf<ChatMessage>()
    repeat(expectedMessages.size) {
        val submissions = awaitChatCalls()
        val appendCount = submissions.count { it.endPoint == appendChatMessageToTranscript }
        if (appendCount != 1) fail(CoroutinePuzzleErrorMessages.chatTranscriptCallsMustBeSerialized())
        verifyOnlyAcceptanceCalls(
            submissions.filterNot { it.endPoint == appendChatMessageToTranscript },
            chatMessageAccepted,
        )
        acceptedMessages += consumeMessageAcceptances(submissions)
        appendedMessages += appendChatMessageToTranscript.expectCall(Unit)
    }
    verifySameValues(appendedMessages, expectedMessages) {
        CoroutinePuzzleErrorMessages.wrongChatMessage(
            actual = appendedMessages.first(),
            expected = expectedMessages.first(),
        )
    }
    return acceptedMessages
}

context(_: CoroutinePuzzleBuilderScope)
private suspend fun consumeRemainingMessageAcceptances(): List<ChatMessage> {
    val submissions = awaitChatCalls()
    verifyOnlyAcceptanceCalls(submissions, chatMessageAccepted)
    return consumeMessageAcceptances(submissions)
}

context(_: CoroutinePuzzleBuilderScope)
private fun verifyMessageAcceptances(
    submissions: List<UnmatchedSubmission<*>>,
    expectedCount: Int,
    message: () -> String,
) {
    if (submissions.size != expectedCount || submissions.any { it.endPoint != chatMessageAccepted }) fail(message())
}

context(_: CoroutinePuzzleBuilderScope)
private fun verifyOnlyAcceptanceCalls(
    submissions: List<UnmatchedSubmission<*>>,
    acceptanceEndpoint: CoroutinePuzzleEndPoint<*, *>,
) {
    verifyUnmatchedSubmissions(submissions, List(submissions.size) { acceptanceEndpoint })
}

context(_: CoroutinePuzzleBuilderScope)
private suspend fun consumeMessageAcceptances(
    submissions: List<UnmatchedSubmission<*>>,
): List<ChatMessage> = coroutineScope {
    List(submissions.count { it.endPoint == chatMessageAccepted }) {
        async { chatMessageAccepted.expectCall(Unit) }
    }.awaitAll()
}

context(_: CoroutinePuzzleBuilderScope)
private suspend fun evaluateDropOldestTypingStatus(
    emitTypingStatus: suspend (TypingStatus) -> Unit,
) = coroutineScope {
    val activeStatus = TypingStatus.Typing
    val staleStatus = TypingStatus.Idle
    val newestStatus = TypingStatus.Typing
    val releaseActiveUpdate = CompletableDeferred<Unit>()
    val activeUpdateStarted = CompletableDeferred<Unit>()
    val activeUpdateWasCanceled = CompletableDeferred<Boolean>()
    val activeUpdate = launch {
        try {
            updateCurrentTypingStatus.expectCall { actual ->
                verify(actual == activeStatus) {
                    CoroutinePuzzleErrorMessages.wrongTypingStatus(actual, activeStatus)
                }
                activeUpdateStarted.complete(Unit)
                releaseActiveUpdate.await()
            }
            activeUpdateWasCanceled.complete(false)
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            activeUpdateWasCanceled.complete(true)
        }
    }

    val activeEmission = launch { emitTypingStatus(activeStatus) }
    activeUpdateStarted.await()
    expectAcceptedTypingStatus(
        activeStatus,
        CoroutinePuzzleErrorMessages.chatTypingStatusMustKeepCollecting(),
    )
    activeEmission.join()

    val staleEmission = launch { emitTypingStatus(staleStatus) }
    val afterStaleStatus = awaitChatCalls()
    failIfTypingUpdateWasReplaced(afterStaleStatus, activeUpdateWasCanceled)
    verifyTypingAcceptance(afterStaleStatus) {
        CoroutinePuzzleErrorMessages.chatTypingStatusMustKeepCollecting()
    }
    consumeTypingAcceptance(staleStatus)
    staleEmission.join()

    val newestEmission = launch { emitTypingStatus(newestStatus) }
    val afterNewestStatus = awaitChatCalls()
    failIfTypingUpdateWasReplaced(afterNewestStatus, activeUpdateWasCanceled)
    verifyTypingAcceptance(afterNewestStatus) {
        CoroutinePuzzleErrorMessages.chatTypingStatusMustDropStaleValues()
    }
    consumeTypingAcceptance(newestStatus)
    newestEmission.join()

    releaseActiveUpdate.complete(Unit)
    activeUpdate.join()
    if (activeUpdateWasCanceled.await()) fail(CoroutinePuzzleErrorMessages.chatTypingUpdatesMustNotBeCanceled())

    val nextUpdate = awaitChatCalls()
    verifyUnmatchedSubmissions(nextUpdate, listOf(updateCurrentTypingStatus))
    val actual = updateCurrentTypingStatus.expectCall(Unit)
    verify(actual == newestStatus) {
        CoroutinePuzzleErrorMessages.wrongTypingStatus(actual, newestStatus)
    }
}

context(_: CoroutinePuzzleBuilderScope)
private suspend fun expectAcceptedTypingStatus(status: TypingStatus, failureMessage: String) {
    val submissions = awaitChatCalls()
    failIfTypingUpdateWasReplaced(submissions, null)
    verifyTypingAcceptance(submissions) { failureMessage }
    consumeTypingAcceptance(status)
}

context(_: CoroutinePuzzleBuilderScope)
private fun verifyTypingAcceptance(
    submissions: List<UnmatchedSubmission<*>>,
    message: () -> String,
) {
    if (submissions.map { it.endPoint } != listOf(chatTypingStatusAccepted)) fail(message())
}

context(_: CoroutinePuzzleBuilderScope)
private suspend fun consumeTypingAcceptance(expected: TypingStatus) {
    val actual = chatTypingStatusAccepted.expectCall(Unit)
    verify(actual == expected) { CoroutinePuzzleErrorMessages.wrongTypingStatus(actual, expected) }
}

context(_: CoroutinePuzzleBuilderScope)
private suspend fun failIfTypingUpdateWasReplaced(
    submissions: List<UnmatchedSubmission<*>>,
    activeUpdateWasCanceled: CompletableDeferred<Boolean>?,
) {
    val replacementUpdateCount = submissions.count { it.endPoint == updateCurrentTypingStatus }
    val activeUpdateCanceled = activeUpdateWasCanceled?.let { it.isCompleted && it.await() } == true
    if (replacementUpdateCount > 0 || activeUpdateCanceled) {
        coroutineScope {
            repeat(submissions.count { it.endPoint == chatTypingStatusAccepted }) {
                launch { chatTypingStatusAccepted.expectCall(Unit) }
            }
            repeat(replacementUpdateCount) {
                launch { updateCurrentTypingStatus.expectCall(Unit) }
            }
        }
        fail(
            CoroutinePuzzleErrorMessages.chatTypingUpdatesMustNotBeCanceled(),
            List(replacementUpdateCount) { updateCurrentTypingStatus },
        )
    }
}

context(_: CoroutinePuzzleBuilderScope)
private fun <T> verifySameValues(actual: List<T>, expected: List<T>, message: () -> String) {
    verify(actual.groupingBy { it }.eachCount() == expected.groupingBy { it }.eachCount(), message)
}

/** Idle chat sources may request their next value; these checks concern downstream writes and acceptances. */
context(_: CoroutinePuzzleBuilderScope)
private suspend fun awaitChatCalls(): List<UnmatchedSubmission<*>> =
    awaitQuiescenceAndGetUnmatchedSubmissions().filterNot {
        it.endPoint == emitIncomingChatMessage || it.endPoint == emitSentChatMessage || it.endPoint == emitTypingStatus
    }
