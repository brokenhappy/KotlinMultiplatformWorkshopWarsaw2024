package com.woutwerkman

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.ComposeUIViewController
import kmpworkshop.client.*
import kmpworkshop.common.ApiKey
import kmpworkshop.common.DiscoGameInstruction
import kmpworkshop.common.asServer
import kmpworkshop.common.clientApiKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map

fun MainViewController() = ComposeUIViewController {
    val server = workshopService.asServer(ApiKey(clientApiKey!!))
    val pressEvents = MutableSharedFlow<Unit>()
    DiscoGame(object: DiscoGameServer {
        override fun backgroundColors(): Flow<Color> = server.discoGameBackground().map { it.toComposeColor() }
        override fun instructions(): Flow<DiscoGameInstruction?> = server.discoGameInstructions(pressEvents)
        override suspend fun submitGuess() {
            println("Pressing!")
            pressEvents.tryEmit(Unit)
        }
    })
}