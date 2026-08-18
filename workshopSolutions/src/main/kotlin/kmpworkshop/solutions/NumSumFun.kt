package kmpworkshop.solutions

import kmpworkshop.api.GetNumberAndSubmit

suspend fun numberSummer(api: GetNumberAndSubmit) {
    val number = api.getNumber()
    api.submit(number + number)
}
