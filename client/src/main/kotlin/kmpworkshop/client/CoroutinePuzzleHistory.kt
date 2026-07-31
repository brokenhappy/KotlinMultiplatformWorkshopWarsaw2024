package kmpworkshop.client

import kmpworkshop.common.CoroutinePuzzleBatchEntry.ExpectationPayload
import kmpworkshop.common.CoroutinePuzzleBatchEntry.SubmissionPayload
import kmpworkshop.common.CoroutinePuzzleEndPointDescriptor
import kmpworkshop.common.CoroutinePuzzleHistoryBatch

fun renderCoroutinePuzzleHistory(batches: List<CoroutinePuzzleHistoryBatch>): String {
    if (batches.isEmpty()) return "No calls have been made"

    val endpointLabels =
        linkedMapOf<CoroutinePuzzleEndPointDescriptor, String>()

    val calls = linkedMapOf<Long, RenderedCall>()

    fun endpointLabel(
        endpoint: CoroutinePuzzleEndPointDescriptor,
    ): String = endpointLabels.getOrPut(endpoint) {
        alphabeticLabel(endpointLabels.size)
    }

    fun activeCall(callId: Long): RenderedCall {
        val call = requireNotNull(calls[callId]) {
            "Event references unknown callId $callId"
        }

        require(call.terminalBatch == null) {
            "Call $callId already completed in batch ${call.terminalBatch!! + 1}"
        }

        return call
    }

    batches.forEachIndexed { batchIndex, batch ->
        when (batch) {
            is CoroutinePuzzleHistoryBatch.Submission -> {
                for ((callId, payload) in batch.entries) {
                    when (payload) {
                        is SubmissionPayload.CallSubmitted -> {
                            require(callId !in calls) {
                                "Call $callId was submitted more than once"
                            }

                            calls[callId] = RenderedCall(
                                endpointLabel = endpointLabel(payload.endPoint),
                                startBatch = batchIndex,
                            )
                        }

                        SubmissionPayload.CallShouldCancel -> {
                            val call = activeCall(callId)

                            require(call.cancelRequestedBatch == null) {
                                "Cancellation was requested more than once for call $callId"
                            }

                            call.cancelRequestedBatch = batchIndex
                        }
                    }
                }
            }
            is CoroutinePuzzleHistoryBatch.Expectation -> {
                for ((callId, payload) in batch.entries) {
                    val call = activeCall(callId)

                    when (payload) {
                        is ExpectationPayload.CallAnswered -> {
                            call.finish(batchIndex, '✓')
                        }

                        ExpectationPayload.CallThrew -> {
                            call.finish(batchIndex, '!')
                        }

                        ExpectationPayload.CallCancellationCompleted -> {
                            require(call.cancelRequestedBatch != null) {
                                "Cancellation completed without being requested for call $callId"
                            }

                            call.finish(batchIndex, 'c')
                        }
                    }
                }
            }
        }
    }

    return buildString {
        appendBatchNumberHeader(batches.size)

        for (call in calls.values) {
            append(call.endpointLabel.padEnd(LABEL_COLUMN_WIDTH))
            append(call.render(batches.size))
            appendLine()
        }

        appendLine()

        endpointLabels.entries
            .chunked(2)
            .forEach { entries ->
                appendLine(
                    entries.joinToString(separator = "   ") { (endpoint, label) ->
                        "$label ${endpoint.description}"
                    },
                )
            }

        appendLine()
        appendLine("● start  ✓ answer  ! throw")
        append("× cancel  c cancelled  > hung")
    }
}

private const val LABEL_COLUMN_WIDTH = 6

private data class RenderedCall(
    val endpointLabel: String,
    val startBatch: Int,
    var cancelRequestedBatch: Int? = null,
    var terminalBatch: Int? = null,
    var terminalSymbol: Char? = null,
) {
    fun finish(
        batchIndex: Int,
        symbol: Char,
    ) {
        terminalBatch = batchIndex
        terminalSymbol = symbol
    }

    fun render(batchCount: Int): String {
        val characters = CharArray(batchCount) { ' ' }
        val finalBatch = terminalBatch ?: (batchCount - 1)

        for (batchIndex in startBatch..finalBatch) {
            characters[batchIndex] = '─'
        }

        characters[startBatch] = '●'

        cancelRequestedBatch?.let { batchIndex ->
            characters[batchIndex] = '×'
        }

        terminalBatch?.let { batchIndex ->
            characters[batchIndex] = requireNotNull(terminalSymbol)
        } ?: run {
            characters[batchCount - 1] = '>'
        }

        return characters.concatToString()
    }
}

private fun StringBuilder.appendBatchNumberHeader(batchCount: Int) {
    val digitCount = batchCount.toString().length

    for (digitPosition in digitCount - 1 downTo 1) {
        append(" ".repeat(LABEL_COLUMN_WIDTH))

        for (batchNumber in 1..batchCount) {
            val divisor = powerOfTen(digitPosition)

            append(
                if (batchNumber >= divisor) {
                    ((batchNumber / divisor) % 10).digitToChar()
                } else {
                    ' '
                },
            )
        }

        appendLine()
    }

    append("batch ")

    for (batchNumber in 1..batchCount) {
        append((batchNumber % 10).digitToChar())
    }

    appendLine()
}

private fun powerOfTen(exponent: Int): Int {
    var result = 1

    repeat(exponent) {
        result *= 10
    }

    return result
}

private fun alphabeticLabel(index: Int): String {
    require(index >= 0)

    var remaining = index
    val result = StringBuilder()

    do {
        result.append(('A'.code + remaining % 26).toChar())
        remaining = remaining / 26 - 1
    } while (remaining >= 0)

    return result.reverse().toString()
}
