package kmpworkshop.server

import kmpworkshop.common.CoroutinePuzzleEndPoint
import kmpworkshop.common.CoroutinePuzzleEndPointId
import kmpworkshop.common.DefaultApis
import kmpworkshop.common.DefaultApis.emitFileToExpose
import kmpworkshop.common.DefaultApis.emitNetworkStrength
import kmpworkshop.common.DefaultApis.emitNumber
import kmpworkshop.common.DefaultApis.shipmentTrackingUpdates
import kmpworkshop.common.DefaultApis.emitShouldMapBeVisible
import kmpworkshop.common.DefaultApis.emitShouldEtaCardBeVisible
import kmpworkshop.common.EndpointDescriptorRegistry

data class EndpointRegistration(
    val actionDescriptionInErrors: String? = null,
    val flowFunctionCall: String? = null,
)

class ServerMetadata internal constructor(
    val collection: EndpointDescriptorRegistry,
    internal val registrations: Map<CoroutinePuzzleEndPoint<*, *>, EndpointRegistration>,
) {
    fun descriptionFor(id: CoroutinePuzzleEndPointId): String =
        registrations[collection.endpointFor(id)]?.actionDescriptionInErrors
            ?: collection.descriptionFor(id)

    fun endpointFor(descriptor: CoroutinePuzzleEndPointId): CoroutinePuzzleEndPoint<*, *> =
        collection.endpointFor(descriptor)

    fun isFlowEndpoint(descriptor: CoroutinePuzzleEndPointId): Boolean =
        registrations[collection.endpointFor(descriptor)]?.flowFunctionCall != null
}

class ServerMetadataBuilder internal constructor(val collection: EndpointDescriptorRegistry) {
    internal val registrations = linkedMapOf<CoroutinePuzzleEndPoint<*, *>, EndpointRegistration>()

    fun <T, R> CoroutinePuzzleEndPoint<T, R>.register(
        actionDescriptionInErrors: String? = null,
        flowFunctionCall: String? = null,
    ) {
        registrations[this] = EndpointRegistration(actionDescriptionInErrors, flowFunctionCall)
    }
}

fun serverMetadataOf(
    collection: EndpointDescriptorRegistry,
    block: ServerMetadataBuilder.() -> Unit,
): ServerMetadata = ServerMetadata(collection, ServerMetadataBuilder(collection).apply(block).registrations)

val defaultServerMetadata = serverMetadataOf(DefaultApis) {
    emitNumber.register(flowFunctionCall = "numbers()")
    DefaultApis.emitIncomingChatMessage.register(flowFunctionCall = "incomingMessages()")
    DefaultApis.emitSentChatMessage.register(flowFunctionCall = "sentMessages()")
    DefaultApis.emitTypingStatus.register(flowFunctionCall = "typingStatusUpdates()")
    shipmentTrackingUpdates.register(flowFunctionCall = "trackingUpdates()")
    emitShouldMapBeVisible.register(flowFunctionCall = "shouldMapBeVisible()")
    emitShouldEtaCardBeVisible.register(flowFunctionCall = "shouldEtaCardBeVisible()")
    emitFileToExpose.register(flowFunctionCall = "currentFileToExpose()")
    emitNetworkStrength.register(flowFunctionCall = "networkStrength()")
}
