package kmpworkshop.client

import kmpworkshop.api.ChatApi
import kmpworkshop.common.CoroutinePuzzleSolutionScope
import kmpworkshop.common.DefaultApis.emitIncomingChatMessage
import kmpworkshop.common.DefaultApis.emitSentChatMessage
import kmpworkshop.common.DefaultApis.emitTypingStatus
import kmpworkshop.common.chatApi
import kotlinx.coroutines.CoroutineScope

context(_: CoroutinePuzzleSolutionScope)
suspend fun chatChannelsScaffolding(
    solution: suspend CoroutineScope.(ChatApi) -> Unit,
) {
    emitIncomingChatMessage.asFlows().use { incomingMessages ->
        emitSentChatMessage.asFlows().use { sentMessages ->
            emitTypingStatus.asFlows().use { typingStatusUpdates ->
                solution(chatApi(incomingMessages, sentMessages, typingStatusUpdates))
            }
        }
    }
}
