package kmpworkshop.server

import kmpworkshop.api.ShipmentUpdate
import kmpworkshop.common.DefaultApis.emitShouldEtaCardBeVisible
import kmpworkshop.common.DefaultApis.emitShouldMapBeVisible
import kmpworkshop.common.DefaultApis.renderShipmentOnMap
import kmpworkshop.common.DefaultApis.shipmentTrackingConnectionLifetime
import kmpworkshop.common.DefaultApis.shipmentTrackingUpdates
import kmpworkshop.common.DefaultApis.updateShipmentEtaCard
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private val firstUpdate = ShipmentUpdate("Distribution centre", 42)
private val secondUpdate = ShipmentUpdate("Out for delivery", 12)

/** Each independently visible widget intentionally opens its own cold tracking connection. */
fun shipmentTrackingIndependentViewsPuzzle() = withVisibilityCollectors { emitMapVisibility, emitEtaVisibility ->
    emitMapVisibility(false)
    emitEtaVisibility(false)
    awaitQuiescenceAndVerifyUnmatchedSubmissions(emptyList()) {
        CoroutinePuzzleErrorMessages.hiddenShipmentWidgetsStartedTracking()
    }
    emitMapVisibility(true)
    emitEtaVisibility(true)
    coroutineScope {
        repeat(2) {
            launch {
                shipmentTrackingConnectionLifetime.expectCanceledCall { expectCancellation() }
            }
        }
        shipmentTrackingUpdates.expectingFlowCollector().use { collectors ->
            coroutineScope {
                repeat(2) {
                    launch {
                        collectors.use { (_, emitUpdate) -> emitUpdate(firstUpdate) }
                    }
                }
                expectBothViews(firstUpdate)
            }
        }
    }
}

/** Both visible widgets must be driven by one eagerly shared upstream collection. */
fun shipmentTrackingSharedConnectionPuzzle() = withVisibilityCollectors { emitMapVisibility, emitEtaVisibility ->
    emitMapVisibility(true)
    emitEtaVisibility(true)
    awaitQuiescenceAndVerifyUnmatchedSubmissions(shipmentTrackingConnectionLifetime) { submissions ->
        if (submissions.count { it == shipmentTrackingConnectionLifetime } != 1) {
            CoroutinePuzzleErrorMessages.shipmentTrackingMustBeShared()
        } else {
            null
        }
    }
    evaluateTrackingConnection {
        shipmentTrackingUpdates.expectingFlowCollector().use { trackingCollectors ->
            trackingCollectors.use { (_, emitUpdate) ->
                emitUpdate(firstUpdate)
                expectBothViews(firstUpdate)
                emitUpdate(secondUpdate)
                expectBothViews(secondUpdate)
            }
        }
    }
}

/** The ETA card becomes visible after the current update and needs replay = 1. */
fun shipmentTrackingLateEtaCardPuzzle() = coroutinePuzzle {
    evaluateTrackingConnection {
        shipmentTrackingUpdates.expectingFlowCollector().use { trackingCollectors ->
            trackingCollectors.use { (_, emitUpdate) ->
                evaluateVisibleViews { emitMapVisibility, emitEtaVisibility ->
                    emitMapVisibility(true)
                    emitUpdate(firstUpdate)
                    renderShipmentOnMap.expectUpdate(firstUpdate)
                    emitEtaVisibility(true)
                    awaitQuiescenceAndVerifyUnmatchedSubmissions(updateShipmentEtaCard) {
                        CoroutinePuzzleErrorMessages.shipmentTrackingNeedsReplay()
                    }
                    updateShipmentEtaCard.expectUpdate(firstUpdate)
                    emitUpdate(secondUpdate)
                    expectBothViews(secondUpdate)
                }
            }
        }
    }
}

/** Visibility collectors may start immediately, but tracking stays dormant until a widget is visible. */
fun shipmentTrackingLazyConnectionPuzzle() = withVisibilityCollectors { emitMapVisibility, emitEtaVisibility ->
    emitMapVisibility(false)
    emitEtaVisibility(false)
    awaitQuiescenceAndVerifyUnmatchedSubmissions(emptyList()) {
        CoroutinePuzzleErrorMessages.shipmentTrackingStartedWhileHidden()
    }
    emitMapVisibility(true)
    evaluateTrackingConnection {
        shipmentTrackingUpdates.expectingFlowCollector().use { trackingCollectors ->
            trackingCollectors.use { (_, emitUpdate) ->
                emitEtaVisibility(true)
                emitUpdate(firstUpdate)
                expectBothViews(firstUpdate)
            }
        }
    }
}

/** When both widgets become hidden, WhileSubscribed must cancel the expensive tracking connection. */
fun shipmentTrackingWhileSubscribedPuzzle() = withVisibilityCollectors { emitMapVisibility, emitEtaVisibility ->
    emitMapVisibility(false)
    emitEtaVisibility(false)
    awaitQuiescenceAndVerifyUnmatchedSubmissions(emptyList()) {
        CoroutinePuzzleErrorMessages.shipmentTrackingStartedWhileHidden()
    }
    emitMapVisibility(true)
    coroutineScope {
        val connectionLifetime = launch {
            shipmentTrackingConnectionLifetime.expectCanceledCall { expectCancellation() }
        }
        shipmentTrackingUpdates.expectingFlowCollector().use { trackingCollectors ->
            trackingCollectors.use { (_, emitUpdate) ->
                emitUpdate(firstUpdate)
                renderShipmentOnMap.expectUpdate(firstUpdate)
                emitEtaVisibility(true)
                updateShipmentEtaCard.expectUpdate(firstUpdate)
                emitMapVisibility(false)
                emitEtaVisibility(false)
                awaitQuiescenceAndVerifyUnmatchedSubmissions(emptyList())
                verify(connectionLifetime.isCompleted) {
                    CoroutinePuzzleErrorMessages.shipmentTrackingDidNotStop()
                }
            }
        }
    }
}

context(_: CoroutinePuzzleBuilderScope)
private suspend fun evaluateTrackingConnection(
    evaluate: suspend context(CoroutinePuzzleBuilderScope) () -> Unit,
) = coroutineScope {
    launch {
        shipmentTrackingConnectionLifetime.expectCanceledCall { expectCancellation() }
    }
    evaluate()
}

private fun withVisibilityCollectors(
    evaluate: suspend context(CoroutinePuzzleBuilderScope) (
        emitMapVisibility: suspend (Boolean) -> Unit,
        emitEtaVisibility: suspend (Boolean) -> Unit,
    ) -> Unit,
) = coroutinePuzzle {
    evaluateVisibleViews(evaluate)
}

context(_: CoroutinePuzzleBuilderScope)
private suspend fun evaluateVisibleViews(
    evaluate: suspend context(CoroutinePuzzleBuilderScope) (
        emitMapVisibility: suspend (Boolean) -> Unit,
        emitEtaVisibility: suspend (Boolean) -> Unit,
    ) -> Unit,
) {
    emitShouldMapBeVisible.expectingFlowCollector().use { mapVisibilityCollectors ->
        emitShouldEtaCardBeVisible.expectingFlowCollector().use { etaVisibilityCollectors ->
            mapVisibilityCollectors.use { (_, emitMapVisibility) ->
                etaVisibilityCollectors.use { (_, emitEtaVisibility) ->
                    evaluate(emitMapVisibility, emitEtaVisibility)
                }
            }
        }
    }
}

context(_: CoroutinePuzzleBuilderScope)
private suspend fun expectBothViews(update: ShipmentUpdate) = coroutineScope {
    launch { renderShipmentOnMap.expectUpdate(update) }
    launch { updateShipmentEtaCard.expectUpdate(update) }
}

context(_: CoroutinePuzzleBuilderScope)
private suspend fun kmpworkshop.common.CoroutinePuzzleEndPoint<ShipmentUpdate, Unit>.expectUpdate(expected: ShipmentUpdate) {
    val actual = expectCall(Unit)
    verify(actual == expected) { CoroutinePuzzleErrorMessages.wrongShipmentUpdate(actual, expected) }
}
