package kmpworkshop.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.serializer
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.use
import kotlin.properties.ReadWriteProperty

internal interface ReadWriteMutableStateFlow<T>: ReadWriteProperty<Any?, T>, StateFlow<T>

internal inline fun <reified T : Any> fileBackedProperty(
    filePath: String,
    noinline defaultValue: () -> T,
): ReadWriteMutableStateFlow<T> = fileBackedProperty(File(filePath), serializer<T>(), defaultValue)

internal fun <T> fileBackedProperty(
    filePath: File,
    serializer: KSerializer<T>,
    defaultValue: () -> T,
): ReadWriteMutableStateFlow<T> = FileBackedProperty(filePath, serializer, defaultValue)

// Dear readers, don't be fooled by thinking that this is remotely reusable, I'm just too lazy to factor rn.
private class FileBackedProperty<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val defaultValue: () -> T,
): ReadWriteMutableStateFlow<T>, MutableStateFlow<T> by MutableStateFlow(defaultValue()) {
    private val cacheLock = ReentrantReadWriteLock()
    init {
        updateValue(true)
    }


    @OptIn(ExperimentalSerializationApi::class)
    private fun readValue(): T {
        cacheLock.read {
            value.let {
                return it
            }
        }

        return updateValue(false)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun updateValue(force: Boolean): T = cacheLock.write {
        value.let {
            if (!force) return@write it
        }

        file.inputStream()
            .use {
                try {
                    Json.decodeFromStream(serializer, it)
                } catch (t: SerializationException) {
                    defaultValue()
                }
            }
            .also { value -> this.value = value }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun writeValue(value: T) {
        cacheLock.write {
            file.outputStream().use { Json.encodeToStream(serializer, value, it) }
            this.value = value
        }
    }

    override operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = readValue()

    override operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) = writeValue(value)
}