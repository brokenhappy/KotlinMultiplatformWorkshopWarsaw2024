import kmpworkshop.client.keyToAccessClientApiKeySecret
import kmpworkshop.client.pathToSecretsInSourceCode
import kmpworkshop.client.workshopService
import kmpworkshop.common.ApiKey
import kmpworkshop.common.ApiKeyRegistrationResult
import kmpworkshop.common.NameVerificationResult
import kmpworkshop.common.getEnvironment
import kotlinx.coroutines.runBlocking
import java.io.File

internal fun printFirstHint() {
    println("""
        Welcome to the workshop! To start, you have to tell me the name that you will be using for the rest of the sessions!
    """.trimIndent())
    requestNameAndSuggestFollowup()
}

// TODO: Combine these 2 steps
internal fun prepareApiKey(apiKeyString: String) {
    val file = File("../common/src/main/resources/secrets.ini")
    file.createNewFile()
    file.readLines()
        .filterNot { it.filterNot { it.isWhitespace() }.startsWith("client-api-key=") }
        .let { listOf("client-api-key = \"$apiKeyString\"") + it }
        .joinToString("\n")
        .let { file.writeText(it) }
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
        is ApiKeyRegistrationResult.Success -> println("""
            Welcome! To verify your registration, do the following:
            1. Open a terminal in IntelliJ by pressing Alt/Opt + F12.
            2. Run the following code to store your api key:
            
            ```kotlin
            fun main() {
               prepareApiKey("${result.key.stringRepresentation}")
            }
            ```
            3. Run the following code to verify your registration:
            
            ```kotlin
            fun main() {
               verifyMyApiKey()
            }
            ```
        """.trimIndent())
    }
}

internal fun verifyMyApiKey() {
    val apiKey = getEnvironment()?.get(keyToAccessClientApiKeySecret) ?: return run {
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
