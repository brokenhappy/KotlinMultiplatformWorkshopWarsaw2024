package kmpworkshop.common

import kmpworkshop.api.*
import kmpworkshop.common.DefaultApis.getAllUserIds
import kmpworkshop.common.DefaultApis.getNumber
import kmpworkshop.common.DefaultApis.legacyCancellationCompletion
import kmpworkshop.common.DefaultApis.queryUserById
import kmpworkshop.common.DefaultApis.submitNumber
import kmpworkshop.common.DefaultApis.emitShouldMapBeVisible
import kmpworkshop.common.DefaultApis.emitShouldEtaCardBeVisible
import kmpworkshop.common.DefaultApis.renderShipmentOnMap
import kmpworkshop.common.DefaultApis.shipmentTrackingUpdates
import kmpworkshop.common.DefaultApis.updateShipmentEtaCard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class QueryFetchFailedForSomeReasonException(message: String? = null) : Exception(message)

context(solutionScope: CoroutinePuzzleSolutionScope)
fun getNumberAndSubmit(): GetNumberAndSubmit = object : GetNumberAndSubmit {
    override suspend fun getNumber(): Int = getNumber.submitCall(Unit)
    override suspend fun submit(sum: Int) { submitNumber.submitCall(sum) }
}

context(solutionScope: CoroutinePuzzleSolutionScope)
fun numberFlowAndSubmit(numbers: Flow<Int>): NumberFlowAndSubmit = object : NumberFlowAndSubmit {
    override fun numbers(): Flow<Int> = numbers
    override suspend fun submit(number: Int) { submitNumber.submitCall(number) }
}

context(solutionScope: CoroutinePuzzleSolutionScope)
fun shipmentTrackingApi(
    updates: Flow<ShipmentUpdate>,
    mapVisibility: Flow<Boolean>,
    etaVisibility: Flow<Boolean>,
): ShipmentTrackingApi = object : ShipmentTrackingApi {
    override fun trackingUpdates(): Flow<ShipmentUpdate> = updates
    override fun shouldMapBeVisible(): Flow<Boolean> = mapVisibility
    override fun shouldEtaCardBeVisible(): Flow<Boolean> = etaVisibility
    override suspend fun renderOnMap(update: ShipmentUpdate) { renderShipmentOnMap.submitCall(update) }
    override suspend fun updateEtaCard(update: ShipmentUpdate) { updateShipmentEtaCard.submitCall(update) }
}

context(solutionScope: CoroutinePuzzleSolutionScope)
fun getUserDatabase(): UserDatabase = object : UserDatabase {
    override suspend fun getAllIds(): List<Int> = getAllUserIds.submitCall(Unit)
    override suspend fun queryUser(id: Int): User = queryUserById.submitCall(id).let { User(it.name, it.age) }
    override suspend fun submit(number: Int) { submitNumber.submitCall(number) }
}

context(solutionScope: CoroutinePuzzleSolutionScope)
fun getUserDatabaseWithLegacyQueryUser(topLevelScope: CoroutineScope): UserDatabaseWithLegacyQueryUser =
    object : UserDatabaseWithLegacyQueryUser {
        override suspend fun getAllIds(): List<Int> = getAllUserIds.submitCall(Unit)

        override fun queryUserWithCallback(
            id: Int,
            onSuccess: (User) -> Unit,
            onError: (Throwable) -> Unit,
        ): QueryHandle {
            val isDone = CompletableDeferred<Unit>()
            return topLevelScope.launch {
                try {
                    try {
                        queryUserById.submitCall(id).let { onSuccess(User(it.name, it.age)) }
                    } catch (failure: ExceptionAcrossRpc) {
                        onError(QueryFetchFailedForSomeReasonException(failure.message))
                    }
                } finally {
                    isDone.complete(Unit)
                }
            }.let { job ->
                object : QueryHandle {
                    override fun cancel(onCancellationFinished: () -> Unit) {
                        topLevelScope.launch {
                            try {
                                job.cancelAndJoin()
                                isDone.await()
                                legacyCancellationCompletion.submitCall(Unit)
                            } finally {
                                onCancellationFinished()
                            }
                        }
                    }
                }
            }
        }

        override suspend fun submit(number: Int) { submitNumber.submitCall(number) }
    }
