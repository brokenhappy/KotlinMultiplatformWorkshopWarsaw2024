package kmpworkshop.client

import kmpworkshop.api.NumberFlowAndSubmit
import kmpworkshop.common.CoroutinePuzzleSolutionScope
import kmpworkshop.common.emitNumber
import kmpworkshop.common.numberFlowAndSubmit
import kotlinx.coroutines.CoroutineScope

context(_: CoroutinePuzzleSolutionScope)
suspend fun flowScaffolding(
    solution: suspend CoroutineScope.(NumberFlowAndSubmit) -> Unit,
) {
    emitNumber.asFlows().use { numbers ->
        solution(numberFlowAndSubmit(numbers))
    }
}
