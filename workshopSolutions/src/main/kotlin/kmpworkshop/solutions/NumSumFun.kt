package kmpworkshop.solutions

import kmpworkshop.api.GetNumberAndSubmit

suspend fun numberSummer(api: GetNumberAndSubmit) {
    val number = api.getNumber1()
    api.submit(number + number)
}
