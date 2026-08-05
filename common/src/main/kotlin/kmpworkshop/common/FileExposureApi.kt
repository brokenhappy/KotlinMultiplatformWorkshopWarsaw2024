package kmpworkshop.common

import kmpworkshop.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

context(solutionScope: CoroutinePuzzleSolutionScope)
fun fileToInternetExposingApi(scope: CoroutineScope): FileToInternetExposingApi = object : FileToInternetExposingApi {
    private var observer: NetworkStrengthObserver? = null

    override val coroutineScope: CoroutineScope = scope

    init {
        scope.launch { while (true) emitNetworkStrength.submitCall(Unit).also { observer?.changed(it) } }
    }

    override fun currentFileToExpose(): Flow<FakeFile> = flow {
        while (true) emit(RemoteFakeFile(emitFileToExpose.submitCall(Unit)))
    }

    override fun registerObserver(observer: NetworkStrengthObserver) {
        require(this.observer == null) { "An observer is already registered" }
        this.observer = observer
    }

    override fun unregisterObserver(observer: NetworkStrengthObserver) {
        require(this.observer === observer) { "Observer was not registered" }
        this.observer = null
    }

    private fun idOf(file: FakeFile): FakeFileId = (file as? RemoteFakeFile)?.id ?: error("Unknown file")
    override suspend fun makeDownloadable(file: FakeFile) { makeFileDownloadable.submitCall(idOf(file)) }
    override suspend fun advertiseFile(file: FakeFile) { advertiseExposedFile.submitCall(idOf(file)) }

    private inner class RemoteFakeFile(val id: FakeFileId) : FakeFile {
        override suspend fun open() { openExposedFile.submitCall(id) }
        override suspend fun close() { importantCleanup { closeExposedFile.submitCall(id) } }
    }
}
