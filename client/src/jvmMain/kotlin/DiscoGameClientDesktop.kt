import androidx.compose.ui.graphics.Color
import kmpworkshop.client.DiscoGame
import kmpworkshop.client.DiscoGameServer
import kmpworkshop.client.toComposeColor
import kmpworkshop.common.DiscoGameInstruction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map

fun main() {
    WorkshopApp("Disco Game Client") { server ->
        val pressEvents = MutableSharedFlow<Unit>()
        DiscoGame(object: DiscoGameServer {
            override fun backgroundColors(): Flow<Color> = server.discoGameBackground().map { it.toComposeColor() }
            override fun instructions(): Flow<DiscoGameInstruction?> = server.discoGameInstructions(pressEvents)
            override suspend fun submitGuess() {
                pressEvents.tryEmit(Unit)
            }
        })
    }
}