package kmpworkshop.client

import kmpworkshop.common.ApiKey
import kmpworkshop.common.getEnvironment

internal const val pathToSecretsInSourceCode = "common/src/main/resources/Secrets.ini"
internal const val keyToAccessClientApiKeySecret = "client-api-key"

internal fun getApiKeyFromEnvironment(): ApiKey =
    ApiKey(getEnvironment()?.get(keyToAccessClientApiKeySecret) ?: wrongApiKeyConfigurationError())

internal fun wrongApiKeyConfigurationError(): Nothing = error("""
    You either your API key configuration got lost, or you haven't gone through registration yet!
    Please ask assistance from the workshop host.
""".trimIndent())
