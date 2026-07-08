package kmpworkshop.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlin.coroutines.resume
import kotlin.time.ExperimentalTime

interface QuiescenceDetectionScope {
    /** Waits for all coroutines inside the lexical scope (Aka lambda) of this detection scope to suspend. */
    suspend fun awaitQuiescence()
}


/** Waits for all coroutines inside the lexical scope (Aka lambda) of this detection scope to suspend. */
context(scope: QuiescenceDetectionScope) suspend fun awaitQuiescence() = scope.awaitQuiescence()

private val quiescenceFunction = AutoBatchedFunctionId<Unit, Unit> { batch ->
    batch.forEach { it.continuation.resume(Unit) }
}

suspend fun <T> withQuiescenceDetection(block: suspend context(QuiescenceDetectionScope) CoroutineScope.() -> T): T {
    @OptIn(ExperimentalTime::class)
    return quiescenceFunction.autoBatchedOnQuiescence {
        context(
            object: QuiescenceDetectionScope {
                override suspend fun awaitQuiescence() {
                    quiescenceFunction.batched(Unit)
                }
            }
        ) {
            block()
        }
    }
}



suspend fun a() {
    withQuiescenceDetection {
        awaitQuiescence()
    }
}