package kmpworkshop.client

import com.woutwerkman.calltreevisualizer.StackTrackingContext
import kmpworkshop.api.ChatApi
import kmpworkshop.api.ChatMessage
import kmpworkshop.api.TypingStatus
import kmpworkshop.common.CoroutinePuzzleSolutionScope
import kmpworkshop.common.DefaultApis.emitIncomingChatMessage
import kmpworkshop.common.DefaultApis.emitSentChatMessage
import kmpworkshop.common.DefaultApis.emitTypingStatus
import kmpworkshop.common.DefaultApis.chatMessageAccepted
import kmpworkshop.common.DefaultApis.chatTypingStatusAccepted
import kmpworkshop.common.chatApi
import kmpworkshop.common.submitCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

context(_: CoroutinePuzzleSolutionScope)
suspend fun chatChannelsScaffolding(
    solution: suspend CoroutineScope.(ChatApi) -> Unit,
) {
    emitIncomingChatMessage.asFlows().use { incomingMessages ->
        emitSentChatMessage.asFlows().use { sentMessages ->
            emitTypingStatus.asFlows().use { typingStatusUpdates ->
                val stackTracker = currentCoroutineContext()[StackTrackingContext] ?: NoOpStackTracker
                withContext(stackTracker) {
                    solution(
                        chatApi(
                            incomingMessages.reportAcceptedMessages(),
                            sentMessages.reportAcceptedMessages(),
                            typingStatusUpdates.reportAcceptedTypingStatuses(),
                        )
                    )
                }
            }
        }
    }
}

context(solutionScope: CoroutinePuzzleSolutionScope)
private fun Flow<ChatMessage>.reportAcceptedMessages() = flow {
    collect { message ->
        emit(message)
        chatMessageAccepted.submitCall(message)
    }
}

context(solutionScope: CoroutinePuzzleSolutionScope)
private fun Flow<TypingStatus>.reportAcceptedTypingStatuses() = flow {
    collect { status ->
        emit(status)
        chatTypingStatusAccepted.submitCall(status)
    }
}
