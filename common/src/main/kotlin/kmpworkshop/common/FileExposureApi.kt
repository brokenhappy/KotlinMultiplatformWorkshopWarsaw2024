package kmpworkshop.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
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

@Serializable @JvmInline value class FakeFileId(val value: Int)

context(solutionScope: CoroutinePuzzleSolutionScope)
fun fileToInternetExposingApi(scope: CoroutineScope): FileToInternetExposingApi =
    object : FileToInternetExposingApi {
        private var observer: NetworkStrengthObserver? = null

        override val coroutineScope: CoroutineScope = scope
        init {
            scope.launch {
                while (true) emitNetworkStrength.submitCall(Unit).also { observer?.changed(it) }
            }
        }

        override fun currentFileToExpose(): Flow<FakeFile> = flow {
            while (true) {
                val id = emitFileToExpose.submitCall(Unit)
                emit(RemoteFakeFile(id))
            }
        }

        override fun registerObserver(observer: NetworkStrengthObserver) {
            require(this.observer == null) { "An observer is already registered" }
            this.observer = observer
        }

        override fun unregisterObserver(observer: NetworkStrengthObserver) {
            require(this.observer === observer) { "Observer was not registered" }
            this.observer = null
        }

        private fun idOf(file: FakeFile): FakeFileId =
            (file as? RemoteFakeFile)?.id ?: error("Unknown file")

        override suspend fun makeDownloadable(file: FakeFile): Unit = makeFileDownloadable.submitCall(idOf(file))
        override suspend fun advertiseFile(file: FakeFile): Unit = advertiseExposedFile.submitCall(idOf(file))

        private inner class RemoteFakeFile(val id: FakeFileId) : FakeFile {
            override suspend fun open(): Unit = openExposedFile.submitCall(id)
            override suspend fun close(): Unit = importantCleanup { closeExposedFile.submitCall(id) }
        }
    }
