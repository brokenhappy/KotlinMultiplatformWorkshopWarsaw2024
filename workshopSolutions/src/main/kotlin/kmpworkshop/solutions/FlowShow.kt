package kmpworkshop.solutions

import kmpworkshop.api.NumberFlowAndSubmit
import kotlinx.coroutines.flow.first

suspend fun showingHowItsFlowing(api: NumberFlowAndSubmit) {
    api.submit(api.numbers().first())
}
