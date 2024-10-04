package kmpworkshop.server

import kmpworkshop.common.ApiKey
import kmpworkshop.common.getEnvironment
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.concurrent.withLock

internal data class Participant(val name: String, val apiKey: ApiKey)

private val databaseAccessLock = ReentrantLock()
private var databaseCache: List<Participant>? = null
internal var participants: List<Participant>
    set(value) {
        databaseAccessLock.withLock {
            File(getEnvironment()!!["server-database-file"]!!)
                .writeText(value.joinToString("\n") { "${it.name},${it.apiKey.stringRepresentation}" })
            databaseCache = value
        }
    }
    get() = databaseAccessLock.withLock {
        databaseCache ?: File(getEnvironment()!!["server-database-file"]!!.also(::println))
            .readLines()
            .map { it.split(",") }
            .map { (name, apiKey) -> Participant(name, ApiKey(apiKey)) }
            .also { databaseCache = it  }
    }