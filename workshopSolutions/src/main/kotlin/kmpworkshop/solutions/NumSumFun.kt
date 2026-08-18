package kmpworkshop.solutions

import kmpworkshop.api.GetNumberAndSubmit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

suspend fun numberSummer(api: GetNumberAndSubmit) {
    val sum = coroutineScope {
        val number1 = async { api.getNumber() }
        api.getNumber() + number1.await()
    }
    api.submit(sum)
}

