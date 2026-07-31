package kmpworkshop.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

inline fun <T> T.sideEffect(function: (T) -> Unit) {
    function(this)
}

@Suppress("LEAKED_IN_PLACE_LAMBDA", "WRONG_INVOCATION_KIND")
@OptIn(ExperimentalContracts::class)
public inline fun <T> MutableStateFlow<T>.updateWithContract(function: (T) -> T) {
    contract { this.callsInPlace(function, InvocationKind.AT_LEAST_ONCE) }
    update(function)
}