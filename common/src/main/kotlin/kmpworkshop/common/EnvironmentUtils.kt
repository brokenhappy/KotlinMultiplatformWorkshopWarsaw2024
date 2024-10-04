package kmpworkshop.common

fun getEnvironment(): Map<String, String>? = currentClassLoader()
    .getResourceAsStream("Secrets.ini")
    ?.bufferedReader()
    ?.readLines()
    ?.filter { !it.trim().startsWith("#") && it.contains("=") }
    ?.associate { it.split("=", limit = 2).let { (k, v) -> k.trim() to v.trimIniValue() } }

private fun String.trimIniValue(): String = this.trim().removeSurrounding("\"")

private fun currentClassLoader(): ClassLoader = (object {}).javaClass.classLoader