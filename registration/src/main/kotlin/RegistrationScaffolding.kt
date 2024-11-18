import kmpworkshop.client.workshopService
import kmpworkshop.common.ApiKey
import kmpworkshop.common.ApiKeyRegistrationResult
import kmpworkshop.common.NameVerificationResult
import kmpworkshop.common.clientApiKey
import kotlinx.coroutines.runBlocking
import java.io.File

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

internal fun registerMyselfByNameThatIWillUseForTheRestOfTheSessions(name: String) {
    when (val result = runBlocking { workshopService.registerApiKeyFor(name) }) {
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
        is ApiKeyRegistrationResult.Success -> {
            prepareApiKey(result.key.stringRepresentation)
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
            NameVerificationResult.AlreadyRegistered -> "You have already registered yourself before, you're good! Please consider helping your peers :)"
        }
    )
}

private fun suggestRunningCode() {
    println("""
        |To proceed take the following steps:
        | 1. Insert the following code into Registration.kt
        |```kotlin
        |fun doRegistration() {
        |    registerMyselfByNameThatIWillUseForTheRestOfTheSessions("<your name here!>") // (Sorry, please use ASCII characters)
        |}
        |```
        | 2. Replace `<your name here!>` with your name.
        | 3. Run the code again by either:
        |    - Clicking the green triangle in the top right corner of your IDE
        |    - Use shortcut ${
                 if ("win" !in System.getProperty("os.name").lowercase()) "Ctrl + R (Default MacOs Shortcut)" 
                 else "Ctrl+F10 (Default Windows/Linux shortcut)"
             }
        |    - If this wasn't clear, you wouldn't be the only one! Please ask the workshop host for help, or ask someone around you.
    """.trimMargin())
}

private fun prepareApiKey(apiKeyString: String) {
    val s = File.separatorChar
    val file = File("..${s}common${s}src${s}commonMain${s}kotlin${s}kmpworkshop${s}common${s}Secrets.kt")
    file.createNewFile()
    file.readLines()
        .filterNot { it.startsWith("val clientApiKey: String? =") }
        .plus("val clientApiKey: String? = \"$apiKeyString\"")
        .joinToString("\n")
        .let { file.writeText(it) }
}
