package kmpworkshop.client

import kmpworkshop.api.ShipmentTrackingApi
import kmpworkshop.common.CoroutinePuzzleSolutionScope
import kmpworkshop.common.DefaultApis.emitShouldEtaCardBeVisible
import kmpworkshop.common.DefaultApis.emitShouldMapBeVisible
import kmpworkshop.common.DefaultApis.shipmentTrackingConnectionLifetime
import kmpworkshop.common.DefaultApis.shipmentTrackingUpdates
import kmpworkshop.common.shipmentTrackingApi
import kmpworkshop.common.submitCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

context(_: CoroutinePuzzleSolutionScope)
suspend fun shipmentTrackingScaffolding(
    solution: suspend CoroutineScope.(ShipmentTrackingApi) -> Unit,
) {
    shipmentTrackingUpdates.asFlows().use { updates ->
        emitShouldMapBeVisible.asFlows().use { mapVisibility ->
            emitShouldEtaCardBeVisible.asFlows().use { etaVisibility ->
                val trackedUpdates = flow {
                    coroutineScope {
                        val lifetime = launch { shipmentTrackingConnectionLifetime.submitCall(Unit) }
                        try {
                            updates.collect { emit(it) }
                        } finally {
                            lifetime.cancelAndJoin()
                        }
                    }
                }
                solution(shipmentTrackingApi(trackedUpdates, mapVisibility, etaVisibility))
            }
        }
    }
}
