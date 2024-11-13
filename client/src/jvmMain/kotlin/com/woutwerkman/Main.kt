package com.woutwerkman

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import kmpworkshop.client.*
import kmpworkshop.common.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.transport.ktor.client.installRPC

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
