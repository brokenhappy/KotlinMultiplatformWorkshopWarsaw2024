package kmpworkshop.solutions

import kmpworkshop.api.ChatApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** The intentionally incomplete starting point for the chat channel puzzles. */
suspend fun writeChatUpdatesDirectly(api: ChatApi) = coroutineScope {
    launch { api.incomingMessages().collect { api.appendToTranscript(it) } }
    launch { api.sentMessages().collect { api.appendToTranscript(it) } }
    launch { api.typingStatusUpdates().collect { api.updateCurrentTypingStatus(it) } }
}
