package kmpworkshop.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

interface GetNumberAndSubmit {
    suspend fun getNumber(): Int
    suspend fun submit(sum: Int)
}

interface NumberFlowAndSubmit {
    fun numbers(): Flow<Int>
    suspend fun submit(number: Int)
}

@Serializable
data class ShipmentUpdate(val checkpoint: String, val etaMinutes: Int)

interface ShipmentTrackingApi {
    fun trackingUpdates(): Flow<ShipmentUpdate>
    fun shouldMapBeVisible(): Flow<Boolean>
    fun shouldEtaCardBeVisible(): Flow<Boolean>
    suspend fun renderOnMap(update: ShipmentUpdate)
    suspend fun updateEtaCard(update: ShipmentUpdate)
}

data class User(val name: String, val age: Int)

interface UserDatabase {
    suspend fun getAllIds(): List<Int>
    suspend fun queryUser(id: Int): User
    suspend fun submit(number: Int)
}

interface UserDatabaseWithLegacyQueryUser {
    suspend fun getAllIds(): List<Int>
    fun queryUserWithCallback(
        id: Int,
        onSuccess: (User) -> Unit,
        onError: (Throwable) -> Unit = { error("Query exception happened, but you didn't handle it!") },
    ): QueryHandle
    suspend fun submit(number: Int)
}

interface QueryHandle {
    fun cancel(onCancellationFinished: () -> Unit = {})
}

interface ExceptionalApi {
    suspend fun clearCaches()
    suspend fun refreshTokens()
    suspend fun reportException(e: Exception)
}

enum class NetworkStrength {
    None, WifiWeak, RoamingWeak, RoamingDataSaving, Roaming, WifiStrong, Ethernet,
}

val NetworkStrength.isStrong: Boolean get() = this >= NetworkStrength.Roaming

fun interface NetworkStrengthObserver {
    fun changed(newStrength: NetworkStrength)
}

interface FakeFile {
    suspend fun open()
    suspend fun close()
}

interface FileToInternetExposingApi {
    fun registerObserver(observer: NetworkStrengthObserver)
    fun unregisterObserver(observer: NetworkStrengthObserver)
    fun currentFileToExpose(): Flow<FakeFile>
    suspend fun makeDownloadable(file: FakeFile)
    suspend fun advertiseFile(file: FakeFile)
    val coroutineScope: CoroutineScope
}

@Serializable
@JvmInline
value class FakeFileId(val value: Int)
