package kmpworkshop.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import kmpworkshop.common.Color
import kmpworkshop.common.PressiveGamePressType
import kmpworkshop.common.WorkshopServer
import kmpworkshop.common.asServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.rpc.streamScoped

private val pressEvents = MutableSharedFlow<PressiveGamePressType>()

fun getFlowOfPressiveGameHints(): Flow<String> = channelFlow {
    workshopService.asServer(getApiKeyFromEnvironment()).playPressiveGame(pressEvents).collect { send(it) }
}

@Composable
internal fun AdaptingBackground(content: @Composable () -> Unit) {
    AdaptingBackground(workshopService.asServer(getApiKeyFromEnvironment()), content)
}

@Composable
fun AdaptingBackground(server: WorkshopServer, content: @Composable () -> Unit) {
    val background = produceState(null as Color?) {
        server.pressiveGameBackground().collect { value = it }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .applyIfNotNull(background.value) { background(it.toComposeColor()) }
    ) {
        content()
    }
}

private fun <T : Any> Modifier.applyIfNotNull(
    value: T?,
    function: Modifier.(T) -> Modifier
): Modifier = value?.let { function(it) } ?: this

fun Color.toComposeColor(): androidx.compose.ui.graphics.Color =
    androidx.compose.ui.graphics.Color(red, green, blue)

suspend fun doSinglePress(): Unit = pressEvents.emit(PressiveGamePressType.SinglePress)
suspend fun doDoublePress(): Unit = pressEvents.emit(PressiveGamePressType.DoublePress)
suspend fun doLongPress(): Unit = pressEvents.emit(PressiveGamePressType.LongPress)
