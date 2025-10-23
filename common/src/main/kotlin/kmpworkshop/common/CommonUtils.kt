package kmpworkshop.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

inline fun <T> T.sideEffect(function: (T) -> Unit) {
    function(this)
}

@OptIn(ExperimentalContracts::class)
inline fun <T> CompletableDeferred<Unit>.completeAfter(function: () -> T): T {
    contract { callsInPlace(function, InvocationKind.EXACTLY_ONCE) }
    val result: T
    completeWithResultOf { function().also { result = it }; }
    return result
}

@OptIn(ExperimentalContracts::class)
inline fun <T> CompletableDeferred<T>.completeWithResultOf(function: () -> T): T {
    contract { callsInPlace(function, InvocationKind.EXACTLY_ONCE) }
    return try {
        function().also { complete(it) }
    } catch (t: Throwable) {
        completeExceptionally(t)
        throw t
    }
}

@Suppress("LEAKED_IN_PLACE_LAMBDA", "WRONG_INVOCATION_KIND")
@OptIn(ExperimentalContracts::class)
public inline fun <T> MutableStateFlow<T>.updateWithContract(function: (T) -> T) {
    contract { this.callsInPlace(function, InvocationKind.AT_LEAST_ONCE) }
    update(function)
}