package kmpworkshop.server

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kmpworkshop.common.ApiKey
import kmpworkshop.common.ApiKeyRegistrationResult
import kmpworkshop.common.NameVerificationResult
import kmpworkshop.common.WorkshopService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.*
import kotlin.coroutines.CoroutineContext

fun main(): Unit = runBlocking {
    launch(Dispatchers.Default) {
        serveSingleService<WorkshopService> { coroutineContext ->
            workshopService(coroutineContext)
        }
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "KMP Workshop") {
            MaterialTheme {
                ServerUi()
            }
        }
    }
}

private fun workshopService(coroutineContext: CoroutineContext): WorkshopService = object : WorkshopService {
    override suspend fun registerApiKeyFor(name: String): ApiKeyRegistrationResult =
        updateServerStateAndGetValue { oldState ->
            when {
                !"[A-z 0-9]{1,20}".toRegex().matches(name) -> oldState to ApiKeyRegistrationResult.NameTooComplex
                oldState.participants.any { it.name == name } -> oldState to ApiKeyRegistrationResult.NameAlreadyExists
                else -> UUID.randomUUID().toString()
                    .let { Participant(name, ApiKey(it)) }
                    .let {
                        oldState.copy(unverifiedParticipants = oldState.unverifiedParticipants + it) to ApiKeyRegistrationResult.Success(
                            it.apiKey
                        )
                    }
            }
        }

    override suspend fun verifyRegistration(key: ApiKey): NameVerificationResult =
        updateServerStateAndGetValue { oldState ->
            val name = oldState
                .unverifiedParticipants
                .firstOrNull { it.apiKey == key }
                ?.name
                ?: return@updateServerStateAndGetValue oldState to NameVerificationResult.ApiKeyDoesNotExist
            val stateWithoutUnverifiedParticipant = oldState.copy(
                unverifiedParticipants = oldState.unverifiedParticipants.filter { it.apiKey != key },
            )
            if (oldState.participants.any { it.name == name })
                return@updateServerStateAndGetValue stateWithoutUnverifiedParticipant to NameVerificationResult.NameAlreadyExists
            stateWithoutUnverifiedParticipant.copy(
                participants = stateWithoutUnverifiedParticipant.participants + Participant(name, key)
            ) to NameVerificationResult.Success
        }

    override val coroutineContext = coroutineContext
}

private fun ServerState.participantFor(apiKey: ApiKey) = participants.firstOrNull { it.apiKey == apiKey }