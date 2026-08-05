package com.kotlinworkshop.test

import kmpworkshop.api.FileToInternetExposingApi
import kmpworkshop.api.NetworkStrengthObserver
import kmpworkshop.api.isStrong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Passes step 1: network changes use collectLatest. Fails step 2: all file work is launched in the API lifetime. */
suspend fun allowPeopleToDownloadExposedFile2(api: FileToInternetExposingApi) = coroutineScope {
    api.currentFileToExpose().collectLatest { file ->
        file.open()
        try {
            api.runOnStrongNetwork2 {
                launch { api.advertiseFile(file) }
                api.makeDownloadable(file)
            }
        } finally { file.close() }
    }
}

/** Passes step 2: network work belongs to the file. Fails step 3: launch still captures the outer solution scope. */
suspend fun allowPeopleToDownloadExposedFile3(api: FileToInternetExposingApi) = coroutineScope {
    api.currentFileToExpose().collectLatest { file ->
        file.open()
        try {
            api.runOnStrongNetwork3 {
                launch { api.advertiseFile(file) }
                api.makeDownloadable(file)
            }
        } finally { file.close() }
    }
}

/** Passes every step: the receiver scope makes advertising a child of the current strong-network task. */
suspend fun allowPeopleToDownloadExposedFile4(api: FileToInternetExposingApi) = coroutineScope {
    api.currentFileToExpose().collectLatest { file ->
        file.open()
        try {
            api.runOnStrongNetwork4 {
                launch { api.advertiseFile(file) }
                api.makeDownloadable(file)
            }
        } finally { file.close() }
    }
}

/** Common mistake: collect cannot request a replacement until all work for the current file returns. */
suspend fun allowPeopleToDownloadExposedFileWithCollect(api: FileToInternetExposingApi) = coroutineScope {
    api.currentFileToExpose().collect { file ->
        file.open()
        try {
            api.runOnStrongNetwork4 {
                launch { api.advertiseFile(file) }
                api.makeDownloadable(file)
            }
        } finally { file.close() }
    }
}

/** Common mistake: advertiseFile suspends forever, so makeDownloadable is never reached. */
suspend fun allowPeopleToDownloadExposedFileWithSequentialAdvertising(api: FileToInternetExposingApi) = coroutineScope {
    api.currentFileToExpose().collectLatest { file ->
        file.open()
        try {
            api.runOnStrongNetwork4 {
                api.advertiseFile(file)
                api.makeDownloadable(file)
            }
        } finally { file.close() }
    }
}

/** Mistake in solution 2: this launch detaches observation and its tasks from the current file. */
fun FileToInternetExposingApi.runOnStrongNetwork2(task: suspend () -> Unit) {
    coroutineScope.launch {
        observeStrongNetwork(task)
    }
}

/** Fix for step 2: cancellation of the file now waits for network observation to finish. */
suspend fun FileToInternetExposingApi.runOnStrongNetwork3(task: suspend () -> Unit) {
    observeStrongNetwork(task)
}

/** Fix for step 3: task receives a fresh lexical scope, so launches inside it cannot escape. */
suspend fun FileToInternetExposingApi.runOnStrongNetwork4(task: suspend CoroutineScope.() -> Unit) {
    observeStrongNetwork { coroutineScope { task() } }
}

private suspend fun FileToInternetExposingApi.observeStrongNetwork(task: suspend () -> Unit) {
    val strong = MutableStateFlow(false)
    val observer = NetworkStrengthObserver { strong.value = it.isStrong }
    registerObserver(observer)
    try {
        strong.collectLatest { if (it) task() }
    } finally {
        unregisterObserver(observer)
    }
}
