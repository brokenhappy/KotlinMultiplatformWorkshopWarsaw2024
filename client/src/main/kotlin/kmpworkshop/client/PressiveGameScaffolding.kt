package kmpworkshop.client

import kmpworkshop.common.PressiveGamePressType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.rpc.streamScoped

private val pressEvents = MutableSharedFlow<PressiveGamePressType>()

fun getFlowOfPressiveGameHints(): Flow<String> = flow {
    streamScoped {
        workshopService.playPressiveGame(getApiKeyFromEnvironment(), pressEvents).collect { emit(it) }
    }
}

suspend fun doSinglePress(): Unit = pressEvents.emit(PressiveGamePressType.SinglePress)
suspend fun doDoublePress(): Unit = pressEvents.emit(PressiveGamePressType.DoublePress)
suspend fun doLongPress(): Unit = pressEvents.emit(PressiveGamePressType.LongPress)