import kmpworkshop.client.workshopService
import kmpworkshop.common.*
import kotlinx.coroutines.runBlocking
import java.io.File

internal fun printFirstHint() {
    println("""
        Welcome to the workshop! To start, you have to tell me the name that you will be using for the rest of the sessions!
    """.trimIndent())
    requestNameAndSuggestFollowup()
}

internal fun registerMyselfByNameThatIWillUseForTheRestOfTheSessions(name: String) {
    when (val result = runBlocking { workshopService.registerApiKeyFor(name) }) {
        ApiKeyRegistrationResult.NameAlreadyExists -> {
            println("""
                Oh no! Someone verified this name before you could!
                Let's restart!
                
            """.trimIndent())
            requestNameAndSuggestFollowup()
        }
        ApiKeyRegistrationResult.NameTooComplex -> {
            println("""
                Sorry, I don't properly handle all names.
                Please pick a name that matches `[A-z 0-9]{1,20}`
            """.trimIndent())
            requestNameAndSuggestFollowup()
        }
        is ApiKeyRegistrationResult.Success -> {
            prepareApiKey(result.key.stringRepresentation)
            println("""
                Welcome! To verify your registration, run the following:
                ```kotlin
                fun main() {
                   verifyMyApiKey()
                }
                ```
            """.trimIndent())
        }
    }
}

internal fun verifyMyApiKey() {
    val apiKey = clientApiKey ?: return run {
        println("""
            Your client API key has not been set up yet!
            Run `registerMyselfByNameThatIWillUseForTheRestOfTheSessions("nameThatYouWantToUse")` to get an explanation of how to proceed! 
        """.trimIndent())
    }
    println(
        when (runBlocking { workshopService.verifyRegistration(ApiKey(apiKey)) }) {
            NameVerificationResult.ApiKeyDoesNotExist -> "Hmm, somehow your client-api-key is invalid, you tried to register with $apiKey. Please restart the process!"
            NameVerificationResult.NameAlreadyExists -> "Oh no, someone who registered with the same name verified before you! Please try again with a different name!"
            NameVerificationResult.Success -> "Congratulations! You've now been verified! You can now help the others to register!"
        }
    )
}

private fun requestNameAndSuggestFollowup() {
    println("""
        To proceed, run the following kotlin code:
        
        ```kotlin
        fun main() {
            registerMyselfByNameThatIWillUseForTheRestOfTheSessions("<your name here!>") // (Sorry, please use ASCII characters)
        }
        ```
    """.trimIndent())
}

private fun prepareApiKey(apiKeyString: String) {
    val file = File("../common/src/commonMain/kotlin/kmpworkshop/common/Secrets.kt")
    file.createNewFile()
    file.readLines()
        .filterNot { it.startsWith("val clientApiKey: String? =") }
        .plus("val clientApiKey: String? = \"$apiKeyString\"")
        .joinToString("\n")
        .let { file.writeText(it) }
}
