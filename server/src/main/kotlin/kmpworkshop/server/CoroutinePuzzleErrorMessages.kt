package kmpworkshop.server

import kmpworkshop.common.CoroutinePuzzleEndPoint

object CoroutinePuzzleErrorMessages {
    fun incorrectSum(numbers: List<Int>, actual: Int): String =
        "You submitted $actual, but ${numbers.joinToString(" + ")} = ${numbers.sum()}. " +
            "Submit the sum of the values returned by getNumber()."

    fun sumCallsMustBeConcurrent(): String =
        "Both getNumber() calls must be in progress at the same time. Start them in separate child coroutines " +
            "(for example with async), then await both results before submitting their sum."

    fun wrongOldestAge(actual: Int, expected: Int): String =
        "You submitted $actual, but the oldest returned user is $expected. Query every returned id and submit the maximum age."

    fun userQueriesMustBeConcurrent(): String =
        "All user queries must be in progress at the same time. Map the ids to async child coroutines, then await all users."

    fun unknownUser(id: Int): String =
        "User id $id does not exist. Only query ids returned by getAllUserIds()."

    fun wrongFlowValue(actual: Int, expected: Int): String =
        "You submitted $actual, but the flow emitted $expected. Submit the value received by the current collector invocation."

    fun exceptionCallsMustBeConcurrent(): String =
        "clearCaches() and refreshTokens() must start concurrently. Launch both inside the same coroutineScope so a child failure cancels its sibling and reaches the surrounding try/catch."

    fun wrongReportedException(expected: String, actual: String?): String =
        "refreshTokens() threw: \"$expected\". Report that same exception; you reported: ${actual?.let { "\"$it\"" } ?: "an exception without a message"}."

    fun cancellationMustFinishFirst(): String =
        "The legacy query reported cancellation, but your suspending wrapper completed before the legacy cancellation callback finished. Suspend until onCancellationFinished is called."

    fun weakWifiExposureStarted(): String =
        "The WiFi is weak, but file exposure already started. Wait for a strong-network emission before downloading or advertising the file."

    fun networkRestartStartedTooEarly(actions: List<CoroutinePuzzleEndPoint<*, *>>): String = """
        |The WiFi became weak and then strong again, but work for the previous strong-network period is still being cancelled.
        |Wait for that work to finish cancellation before starting the replacement work. Already started:
        |${actions.joinToString("\n| ") { "  - ${it.descriptor.description}" }}
    """.trimMargin()

    fun wrongFile(action: String, expectedRole: String): String =
        "You tried to $action the wrong file. It must be $expectedRole. Keep each file's work inside that file's coroutine scope."

    fun wrongEndpointArgument(expected: Any?, actual: Any?): String =
        "This call used $actual, but the currently active file is $expected. Use the file received by the current collector invocation."

}
