package com.woutwerkman

import kmpworkshop.client.*
import kmpworkshop.common.ApiKey
import kmpworkshop.common.WorkshopStage
import kmpworkshop.common.asServer
import kmpworkshop.common.clientApiKey
import kotlinx.coroutines.flow.first

suspend fun main() {
    try {
        println("\u001B[92mTHIS IS THE START OF THE APP OUTPUT ########################################################################################################\u001B[0m")
        val server = workshopService.asServer(ApiKey(clientApiKey ?: error("You need to finish registration first!")))
        when (server.currentStage().first()) {
            WorkshopStage.Registration -> println("We are in the Registration stage. Please run `Registration` configuration instead!")
            WorkshopStage.PalindromeCheckTask -> checkCodePuzzle("PalindromeCheck.kt", solution = ::doPalindromeCheckOn)
            WorkshopStage.FindMinimumAgeOfUserTask -> checkCodePuzzle("MinimumAgeFinding.kt", solution = ::serializableFindMinimumAgeOf)
            WorkshopStage.FindOldestUserTask -> checkCodePuzzle("OldestUserFinding.kt", solution = ::serializableFindOldestUserAmong)
            WorkshopStage.SliderGameStage,
            WorkshopStage.PressiveGameStage,
            WorkshopStage.DiscoGame -> WorkshopApp("Workshop Client") { ClientEntryPoint(server) }
        }
    } finally {
        println("\u001B[92mTHIS IS THE \u001B[91mEND\u001B[0m\u001B[92m OF THE APP OUTPUT ########################################################################################################\u001B[0m")
    }
}
