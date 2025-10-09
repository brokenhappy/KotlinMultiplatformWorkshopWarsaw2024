package com.woutwerkman

import kmpworkshop.client.*
import kmpworkshop.common.ApiKey
import kmpworkshop.common.WorkshopStage
import kmpworkshop.common.asServer
import kmpworkshop.common.clientApiKey
import kmpworkshop.common.getNumberAndSubmit
import kmpworkshop.common.numberFlowAndSubmit
import kotlinx.coroutines.flow.first

suspend fun main() {
    try {
        println("\u001B[92mTHIS IS THE START OF THE APP OUTPUT ########################################################################################################\u001B[0m")
        val server = workshopService.asServer(ApiKey(clientApiKey ?: error("You need to finish registration first!")))
        when (val stage = server.currentStage().first()) {
            WorkshopStage.Registration -> println("We are in the Registration stage. Please run `Registration` configuration instead!")
            WorkshopStage.PalindromeCheckTask -> checkCodePuzzle(stage.name, solution = ::doPalindromeCheckOn)
            WorkshopStage.FindMinimumAgeOfUserTask -> checkCodePuzzle(stage.name, solution = ::serializableFindMinimumAgeOf)
            WorkshopStage.FindOldestUserTask -> checkCodePuzzle(stage.name, solution = ::serializableFindOldestUserAmong)
            WorkshopStage.SumOfTwoIntsSlow,
            WorkshopStage.SumOfTwoIntsFast -> checkCoroutinePuzzle(stage.name, ::numberSummer) { getNumberAndSubmit() }
            WorkshopStage.SimpleFlow,
            WorkshopStage.CollectLatest -> checkCoroutinePuzzle(stage.name, ::showingHowItsFlowing) { numberFlowAndSubmit() }
            WorkshopStage.SliderGameStage,
            WorkshopStage.PressiveGameStage,
            WorkshopStage.DiscoGame -> WorkshopApp("Workshop Client") { ClientEntryPoint(server) }
        }
    } finally {
        println("\u001B[92mTHIS IS THE \u001B[91mEND\u001B[0m\u001B[92m OF THE APP OUTPUT ########################################################################################################\u001B[0m")
    }
}
