package kmpworkshop.server

import kmpworkshop.api.ChatMessage
import kmpworkshop.api.ShipmentUpdate
import kmpworkshop.api.TypingStatus
import kmpworkshop.common.CoroutinePuzzleEndPoint

object CoroutinePuzzleErrorMessages {
    fun incorrectSum(numbers: List<Int>, actual: Int): String = """
        |You submitted $actual, but ${numbers.joinToString(" + ")} = ${numbers.sum()}.
        |Submit the sum of the values returned by getNumber().
    """.trimMargin()

    fun sumCallsMustBeConcurrent(): String = """
        Both getNumber() calls must be in progress at the same time. Start them in separate child coroutines
        (for example with async), then await both results before submitting their sum.
    """.trimIndent()

    fun sumCancellationMustCancelBothCalls(): String = """
        Your function was cancelled while getNumber() calls were still running. Cancel every in-flight call too, so no
        work remains after your function stops.
    """.trimIndent()

    fun sumExceptionMustCancelOtherCall(): String = """
        A getNumber() call failed, but another call was left running. Cancel the other in-flight call, then let the
        original error escape from your function.
    """.trimIndent()

    fun sumCancellationMustFinishBeforeExceptionEscapes(): String = """
        Your function reported the failure before the other getNumber() call finished cancelling. Wait for cancellation
        to complete before letting the original error escape.
    """.trimIndent()

    fun wrongOldestAge(actual: Int, expected: Int): String = """
        |You submitted $actual, but the oldest returned user is $expected.
        |Query every returned id and submit the maximum age.
    """.trimMargin()

    fun userQueriesMustBeConcurrent(): String = """
        All user queries must be in progress at the same time. Map the ids to async child coroutines, then await all users.
    """.trimIndent()

    fun unknownUser(id: Int): String = """
        |User id $id does not exist. Only query ids returned by getAllUserIds().
    """.trimMargin()

    fun wrongFlowValue(actual: Int, expected: Int): String = """
        |You submitted $actual, but the flow emitted $expected.
        |Submit the value received by the current collector invocation.
    """.trimMargin()

    fun chatTranscriptCallsMustBeSerialized(): String = """
        |Two chat-message collectors started appending to the transcript at once.
        |The transcript must have one active writer: do not start another append while one is still running.
    """.trimMargin()

    fun chatMessageCollectorsMustNotWaitForTranscript(): String = """
        |A chat-message collector stopped accepting messages while a transcript write was in progress.
        |Message sources must continue accepting new messages while the single transcript writer is busy.
    """.trimMargin()

    fun chatTypingUpdatesMustNotBeCanceled(): String = """
        |A newer typing status cancelled an update that was already in progress.
        |Once applying a status has started, let it finish; only waiting status may be replaced.
    """.trimMargin()

    fun chatTypingStatusMustKeepCollecting(): String = """
        |A newer typing status could not be accepted while the current status was being applied.
        |Let the current update finish, while retaining the most recent status that arrives in the meantime.
    """.trimMargin()

    fun chatTypingStatusMustDropStaleValues(): String = """
        |A newer typing status was stuck behind an older pending status.
        |Typing status is current state: after the active update finishes, apply only the newest pending status.
    """.trimMargin()

    fun wrongChatMessage(actual: ChatMessage, expected: ChatMessage): String = """
        |The transcript appended $actual, but the next accepted message was $expected.
        |Forward every accepted chat message to the single transcript writer without changing it.
    """.trimMargin()

    fun wrongTypingStatus(actual: TypingStatus, expected: TypingStatus): String = """
        |The current typing status was updated to $actual, but the newest pending status was $expected.
        |Apply the active update completely, then apply only the most recent status received while it was running.
    """.trimMargin()

    fun shipmentTrackingMustBeShared(): String = """
        The map and ETA card each opened their own shipment-tracking connection. Both widgets should receive updates
        while only one connection is running.
    """.trimIndent()

    fun shipmentTrackingNeedsReplay(): String = """
        The ETA card became visible after an update and stayed empty until another update arrived. It should show the
        most recently received tracking update as soon as it becomes visible.
    """.trimIndent()

    fun shipmentTrackingStartedWhileHidden(): String = """
        The shipment-tracking connection opened while both widgets were hidden. It should remain closed until a widget
        actually starts observing tracking updates.
    """.trimIndent()

    fun hiddenShipmentWidgetsStartedTracking(): String = """
        The map and ETA card started receiving tracking updates while they were hidden. They should begin observing
        only while their visibility is true.
    """.trimIndent()

    fun shipmentTrackingDidNotStop(): String = """
        The shipment-tracking connection kept running after both widgets became hidden. It should close after the last
        widget stops observing tracking updates.
    """.trimIndent()

    fun wrongShipmentUpdate(actual: ShipmentUpdate, expected: ShipmentUpdate): String = """
        |The tracking source reported $expected, but the widget displayed $actual.
        |The widget should display the same checkpoint and ETA as the latest tracking update.
    """.trimMargin()

    fun exceptionCallsMustBeConcurrent(): String = """
        clearCaches() and refreshTokens() must start concurrently. Launch both inside the same coroutineScope so a child
        failure cancels its sibling and reaches the surrounding try/catch.
    """.trimIndent()

    fun wrongReportedException(expected: String, actual: String?): String = """
        |refreshTokens() threw: "$expected". Report that same exception; you reported:
        |${actual?.let { "\"$it\"" } ?: "an exception without a message"}.
    """.trimMargin()

    fun cancellationMustFinishFirst(): String = """
        The legacy query reported cancellation, but your suspending wrapper completed before the legacy cancellation
        callback finished. Suspend until onCancellationFinished is called.
    """.trimIndent()

    fun weakWifiExposureStarted(): String = """
        The WiFi is weak, but file exposure already started. Wait for a strong-network emission before downloading or
        advertising the file.
    """.trimIndent()

    fun networkRestartStartedTooEarly(
        actions: List<CoroutinePuzzleEndPoint<*, *>>,
        metadata: ServerMetadata = defaultServerMetadata,
    ): String = """
        |The WiFi became weak and then strong again, but work for the previous strong-network period is still being cancelled.
        |Wait for that work to finish cancellation before starting the replacement work. Already started:
        |${actions.joinToString("\n| ") { "  - ${metadata.descriptionFor(it.id)}" }}
    """.trimMargin()

    fun wrongFile(action: String, expectedRole: String): String = """
        |You tried to $action the wrong file. It must be $expectedRole.
        |Keep each file's work inside that file's coroutine scope.
    """.trimMargin()

    fun wrongEndpointArgument(expected: Any?, actual: Any?): String = """
        |This call used $actual, but the currently active file is $expected.
        |Use the file received by the current collector invocation.
    """.trimMargin()

}
