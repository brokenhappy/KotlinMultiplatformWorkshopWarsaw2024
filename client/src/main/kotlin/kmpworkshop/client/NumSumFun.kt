package kmpworkshop.client

import kmpworkshop.common.GetNumberAndSubmit

suspend fun numberSummer(api: GetNumberAndSubmit) {
    val number = api.getNumber()
    api.submit(number + number)
}