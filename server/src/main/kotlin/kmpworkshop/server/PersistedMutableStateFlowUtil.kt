package kmpworkshop.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File

internal suspend inline fun <reified T> MutableStateFlow<T>.persisting(file: File): Nothing =
    persisting(file, serializer())

internal suspend fun <T> MutableStateFlow<T>.persisting(
    file: File,
    serializer: KSerializer<T>,
): Nothing = coroutineScope {
    try {
        value = withContext(Dispatchers.IO) { Json.decodeFromString(serializer, file.readText()) }
    } catch (se: SerializationException) {
        println(file.readText())
        se.printStackTrace()
    } catch (se: IllegalArgumentException) {
        println(file.readText())
        se.printStackTrace()
    }
    drop(1) // Don't write initial state!
        .conflate()
        .collect { state -> withContext(Dispatchers.IO) { file.writeText(Json.encodeToString(serializer, state)) } }
    error("Impossible")
}