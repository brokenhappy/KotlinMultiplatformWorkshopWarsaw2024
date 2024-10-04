package kmpworkshop.server

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


inline fun <reified T : Any> fileBackedProperty(
    filePath: String,
    noinline defaultValue: () -> T,
): ReadWriteProperty<Any?, T> = fileBackedProperty(File(filePath), serializer<T>(), defaultValue)

fun <T> fileBackedProperty(
    filePath: File,
    serializer: KSerializer<T>,
    defaultValue: () -> T,
): ReadWriteProperty<Any?, T> = FileBackedProperty(filePath, serializer, defaultValue)

private class FileBackedProperty<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val defaultValue: () -> T,
): ReadWriteProperty<Any?, T> {
    private val cacheLock = ReentrantReadWriteLock()
    @Volatile private var cachedValue: T? = null

    @OptIn(ExperimentalSerializationApi::class)
    private fun readValue(): T {
        cacheLock.read {
            cachedValue?.let {
                return it
            }
        }

        return cacheLock.write {
            cachedValue?.let {
                return@write it
            }

            file.inputStream()
                .use {
                    try {
                        Json.decodeFromStream(serializer, it)
                    } catch (t: SerializationException) {
                        defaultValue()
                    }
                }
                .also { value -> cachedValue = value }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun writeValue(value: T) {
        cacheLock.write {
            file.outputStream().use { Json.encodeToStream(serializer, value, it) }
            cachedValue = value
        }
    }

    override operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = readValue()

    override operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) = writeValue(value)
}