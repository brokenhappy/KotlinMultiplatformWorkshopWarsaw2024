package kmpworkshop.common

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.CoroutineContext

/**
 * Changes the behavior when called under [withInterceptingDispatcher].
 *
 * This is useful when you would hand off work to a [Flow] that produces outside the [withInterceptingDispatcher] call.
 * For [withInterceptingDispatcher]s that detect quiescence, that moment could look like the coroutine tree is stuck (quiescent).
 * However, it's not stuck, it will unblock at some point soon, when the [Flow] produces again.
 *
 * Therefore, we fake a dispatch start and end every time we switch from our coroutine context to the producer's context.
 */
fun <T> Flow<T>.assumingProducerLivesOnUninterceptedDispatcher(context: CoroutineContext? = null): Flow<T> = flow {
    val context = context ?: currentCoroutineContext()
    context.fakeInterceptingDispatchStart()
    try {
        collect {
            context.fakeInterceptingDispatchedRunnableCompleted()
            try {
                emit(it)
            } finally {
                context.fakeInterceptingDispatchStart()
            }
        }
    } finally {
        context.fakeInterceptingDispatchedRunnableCompleted()
    }
}