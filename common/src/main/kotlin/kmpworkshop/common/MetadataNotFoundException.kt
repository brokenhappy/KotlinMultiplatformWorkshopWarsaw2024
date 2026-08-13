package kmpworkshop.common

class MetadataNotFoundException(
    val endpointId: CoroutinePuzzleEndPointId,
) : IllegalStateException("No metadata was found for coroutine puzzle endpoint ${endpointId.stringValue}.")
