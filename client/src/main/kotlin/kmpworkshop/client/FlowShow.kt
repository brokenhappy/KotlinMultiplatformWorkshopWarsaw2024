package kmpworkshop.client

import kmpworkshop.common.NumberFlowAndSubmit
import kotlinx.coroutines.flow.first

suspend fun showingHowItsFlowing(api: NumberFlowAndSubmit) {
    api.submit(api.numbers().first())
}