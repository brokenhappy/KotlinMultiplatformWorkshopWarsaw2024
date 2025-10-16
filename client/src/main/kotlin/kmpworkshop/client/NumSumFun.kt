package kmpworkshop.client

import kmpworkshop.common.GetNumberAndSubmit

suspend fun numberSummer(api: GetNumberAndSubmit) {
    api.submit(api.getNumber() + api.getNumber())
}