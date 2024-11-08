package kmpworkshop.client

internal fun wrongApiKeyConfigurationError(): Nothing = error("""
    You either your API key configuration got lost, or you haven't gone through registration yet!
    Please ask assistance from the workshop host.
""".trimIndent())
