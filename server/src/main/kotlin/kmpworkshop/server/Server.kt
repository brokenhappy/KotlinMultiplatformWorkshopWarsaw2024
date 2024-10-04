package kmpworkshop.server

import kmpworkshop.common.ApiKey
import kmpworkshop.common.ApiKeyRegistrationResult
import kmpworkshop.common.NameVerificationResult
import kmpworkshop.common.WorkshopService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*

fun main(): Unit = runBlocking {
    val nameLock = Mutex()
    val currentRegisteringPeople = mutableMapOf<ApiKey, String>() // TODO: Keep in file? Or do I trust that I won't need restart?
    serveSingleService<WorkshopService> { coroutineContext ->
        object: WorkshopService {
            override suspend fun registerApiKeyFor(name: String): ApiKeyRegistrationResult {
                return nameLock.withLock {
                    when {
                        !"[A-z 0-9]{1,20}".toRegex().matches(name) -> ApiKeyRegistrationResult.NameTooComplex
                        participants.any { it.name == name } -> ApiKeyRegistrationResult.NameAlreadyExists
                        else -> UUID.randomUUID().toString()
                            .let { ApiKey(it) }
                            .also { currentRegisteringPeople[it] = name }
                            .let { ApiKeyRegistrationResult.Success(it) }
                    }
                }
            }

            override suspend fun verifyRegistration(key: ApiKey): NameVerificationResult = nameLock.withLock {
                println("Verifying $key")
                currentRegisteringPeople.forEach { (k, v) -> println("$k -> $v") }
                val name = currentRegisteringPeople[key] ?: return NameVerificationResult.ApiKeyDoesNotExist
                currentRegisteringPeople.remove(key)
                if (participants.any { it.name == name }) return NameVerificationResult.NameAlreadyExists
                participants += Participant(name, key)
                return NameVerificationResult.Success
            }

            override val coroutineContext = coroutineContext
        }
    }
}