package kmpworkshop.client

import kmpworkshop.common.CoroutinePuzzleEndPoint
import kmpworkshop.common.DefaultApis
import kmpworkshop.common.EndpointDescriptorRegistry
import kmpworkshop.common.WorkshopApiService
import kmpworkshop.common.ApiKey
import kmpworkshop.common.CoroutinePuzzleEndPointId
import kmpworkshop.common.WorkshopServer
import kmpworkshop.common.asServer as asServerWithHash

data class EndpointRegistration(
    val onStartDescription: ((Any?) -> String)? = null,
    val onCollectStartDescription: ((Any?) -> String)? = null,
    val onEmissionDescription: ((Any?) -> String)? = null,
    val isHiddenInHistory: Boolean = false,
    val isFlowEndpoint: Boolean = false,
)

class ClientMetadata internal constructor(
    private val registry: EndpointDescriptorRegistry,
    internal val registrations: Map<CoroutinePuzzleEndPoint<*, *>, EndpointRegistration>,
) {
    fun descriptionFor(id: CoroutinePuzzleEndPointId): String = registry.descriptionFor(id)

    fun isHiddenInHistory(id: CoroutinePuzzleEndPointId): Boolean =
        registry.endpointFor(id).let { registrations[it]?.isHiddenInHistory ?: false }

    fun isFlowEndpoint(id: CoroutinePuzzleEndPointId): Boolean =
        registry.endpointFor(id).let { registrations[it]?.isFlowEndpoint ?: false }
}

class ClientMetadataBuilder internal constructor() {
    internal val registrations = linkedMapOf<CoroutinePuzzleEndPoint<*, *>, EndpointRegistration>()

    fun <T, R> CoroutinePuzzleEndPoint<T, R>.register(
        onStartDescription: ((T) -> String)? = null,
        onCollectStartDescription: ((T) -> String)? = null,
        onEmissionDescription: ((R) -> String)? = null,
        isHiddenInHistory: Boolean = false,
        isFlowEndpoint: Boolean = false,
    ) {
        registrations[this] = EndpointRegistration(
            onStartDescription = onStartDescription?.let { fn -> { value -> fn(value as T) } },
            onCollectStartDescription = onCollectStartDescription?.let { fn -> { value -> fn(value as T) } },
            onEmissionDescription = onEmissionDescription?.let { fn -> { value -> fn(value as R) } },
            isHiddenInHistory = isHiddenInHistory,
            isFlowEndpoint = isFlowEndpoint,
        )
    }
}

fun clientMetadataOf(
    collection: EndpointDescriptorRegistry,
    block: ClientMetadataBuilder.() -> Unit,
): ClientMetadata = ClientMetadata(collection, ClientMetadataBuilder().apply(block).registrations)

val defaultClientMetadata: ClientMetadata = clientMetadataOf(DefaultApis) {
    DefaultApis.callLifetime.register(isHiddenInHistory = true)
    DefaultApis.callIsDone.register(isHiddenInHistory = true)
    DefaultApis.emitNumber.register(isFlowEndpoint = true)
    DefaultApis.shipmentTrackingUpdates.register(isFlowEndpoint = true)
    DefaultApis.emitShouldMapBeVisible.register(isFlowEndpoint = true)
    DefaultApis.emitShouldEtaCardBeVisible.register(isFlowEndpoint = true)
    DefaultApis.shipmentTrackingConnectionLifetime.register(isHiddenInHistory = true)
    DefaultApis.emitFileToExpose.register(isFlowEndpoint = true)
    DefaultApis.emitNetworkStrength.register(isFlowEndpoint = true)
}

context(clientMetadata: ClientMetadata)
fun WorkshopApiService.asServer(apiKey: ApiKey): WorkshopServer =
    asServerWithHash(apiKey, clientMetadataHash = DefaultApis.endpointHash())
