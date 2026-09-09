package kmpworkshop.client

import kmpworkshop.api.NumberFlowAndSubmit
import kmpworkshop.common.CoroutinePuzzleSolutionScope
import kmpworkshop.common.DefaultApis.emitNumber
import kmpworkshop.common.numberFlowAndSubmit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope

context(_: CoroutinePuzzleSolutionScope)
suspend fun flowScaffolding(
    solution: suspend CoroutineScope.(NumberFlowAndSubmit) -> Unit,
) = coroutineScope {
    solution(numberFlowAndSubmit(emitNumber.asFlow()))
}
