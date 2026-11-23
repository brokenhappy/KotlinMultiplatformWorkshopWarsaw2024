import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kmpworkshop.client.withWorkshopService
import kmpworkshop.common.ApiKey
import kmpworkshop.common.ApiKeyRegistrationResult
import kmpworkshop.common.NameVerificationResult
import kmpworkshop.common.WorkshopApiService
import kmpworkshop.common.clientApiKey
import kmpworkshop.common.clientInvitationCode
import kmpworkshop.common.normalizeWorkshopInvitationCode
import kmpworkshop.common.saveClientCredentials
import kotlinx.coroutines.runBlocking

fun main() {
    try {
        println("\u001B[92mTHIS IS THE START OF THE APP OUTPUT ########################################################################################################\u001B[0m")
        doRegistration()
    } finally {
        println("\u001B[92mTHIS IS THE \u001B[91mEND\u001B[0m\u001B[92m OF THE APP OUTPUT ########################################################################################################\u001B[0m")
    }
}

internal fun printFirstHint() {
    println("""
        Welcome to the workshop! To start, you have to tell me the name that you will be using for the rest of the sessions!
    """.trimIndent())
    suggestRunningCode()
}

internal fun registerMyselfWithTheNameIWillUseForTheRestOfTheSessionsAndMyInvitationCode(
    name: String,
    invitationCode: String,
) {
    val code = normalizeWorkshopInvitationCode(invitationCode) ?: run {
        println("Enter the invitation code provided by your workshop host on one line.")
        return
    }
    when (val result = runRegistrationRequest(code) { registerApiKeyFor(name) } ?: return) {
        ApiKeyRegistrationResult.NameAlreadyExists -> {
            println("""
                Oh no! Someone verified this name before you could!
                Let's restart!
                
            """.trimIndent())
            suggestRunningCode()
        }
        ApiKeyRegistrationResult.NameTooComplex -> {
            println("""
                Sorry, I don't properly handle all names.
                Please pick a name that matches `[A-z 0-9]{1,20}`
            """.trimIndent())
            suggestRunningCode()
        }
        ApiKeyRegistrationResult.CapacityReached -> {
            println("Registration is currently full. Please ask the workshop host for help.")
        }
        is ApiKeyRegistrationResult.Success -> {
            saveClientCredentials(result.key, code)
            println("""
                Welcome! To verify your registration, run the following:
                ```kotlin
                fun doRegistration() {
                   verifyMyApiKey()
                }
                ```
            """.trimIndent())
        }
    }
}

internal fun verifyMyApiKey() {
    val apiKey = clientApiKey ?: return run {
        println("Your client API key has not been set up yet!")
        suggestRunningCode()
    }
    val invitationCode = clientInvitationCode ?: return run {
        println("Set WORKSHOP_INVITATION_CODE to the code provided by your workshop host, then try again.")
    }
    println(
        when (runRegistrationRequest(invitationCode) { verifyRegistration(ApiKey(apiKey)) } ?: return) {
            NameVerificationResult.ApiKeyDoesNotExist -> "Hmm, your client API key is invalid. Please restart the process!"
            NameVerificationResult.NameAlreadyExists -> "Oh no, someone who registered with the same name verified before you! Please try again with a different name!"
            NameVerificationResult.Success -> "Congratulations! You've now been verified! You can now help the others to register!"
            NameVerificationResult.AlreadyRegistered -> "You have already registered yourself before, you're good! Please consider helping your peers :)"
        }
    )
}

private fun <T> runRegistrationRequest(invitationCode: String, request: suspend WorkshopApiService.() -> T): T? = try {
    runBlocking { withWorkshopService(invitationCode, action = request) }
} catch (failure: ClientRequestException) {
    if (failure.response.status != HttpStatusCode.Unauthorized) throw failure
    println("The workshop server rejected your invitation code. Check the code with your workshop host and try again.")
    null
}

private fun suggestRunningCode() {
    println("""
        |To proceed take the following steps:
        | 1. Insert the following code into Registration.kt
        |```kotlin
        |fun doRegistration() {
        |    registerMyselfWithTheNameIWillUseForTheRestOfTheSessionsAndMyInvitationCode(
        |        name = "<your name here!>",
        |        invitationCode = "<your invitation code>",
        |    )
        |}
        |```
        | 2. Enter your name (ASCII characters only) and the invitation code from your workshop host.
        | 3. Run the code again by either:
        |    - Clicking the green triangle in the top right corner of your IDE
        |    - Use shortcut ${
                 if ("win" !in System.getProperty("os.name").lowercase()) "Ctrl + R (Default MacOs Shortcut)" 
                 else "Ctrl+F10 (Default Windows/Linux shortcut)"
             }
        |    - If this wasn't clear, you wouldn't be the only one! Please ask the workshop host for help, or ask someone around you.
    """.trimMargin())
}
