package kmpworkshop.common

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.*
import kotlinx.serialization.serializer
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

@OptIn(ExperimentalAtomicApi::class)
@PublishedApi
internal val callIdCounter = AtomicLong(0L)

@Serializable data class CallId(val id: Long, val parentCallId: CallId?)


@Serializable
sealed class RpcQ<in T> {
    @Serializable
    data class Call<T>(val callId: CallId, val argument: T) : RpcQ<T>()
    @Serializable(with = Confirmation.Serializer::class)
    data class Confirmation(val callId: CallId) : RpcQ<Any?>() {
        object Serializer: KSerializer<Confirmation> by callIdWrapperSerializer(::Confirmation, { it.callId })
    }
    @Serializable(with = Cancellation.Serializer::class)
    data class Cancellation(val callId: CallId) : RpcQ<Any?>() {
        object Serializer: KSerializer<Cancellation> by callIdWrapperSerializer(::Cancellation, { it.callId })
    }

    companion object {
        private inline fun <reified T> callIdWrapperSerializer(
            crossinline mapper: (CallId) -> T,
            crossinline getCallId: (T) -> CallId,
        ) = object: KSerializer<T> {
            override val descriptor: SerialDescriptor =
                buildClassSerialDescriptor(T::class.qualifiedName!!) { element<Long>("callId") }

            override fun serialize(encoder: Encoder, value: T) {
                encoder.encodeStructure(descriptor) {
                    encodeSerializableElement(descriptor, 0, serializer<CallId>(), getCallId(value))
                }
            }

            override fun deserialize(decoder: Decoder): T = decoder.decodeStructure(descriptor) {
                var callId: CallId? = null
                while (true) {
                    when (val index = decodeElementIndex(descriptor)) {
                        0 -> callId = decodeSerializableElement(descriptor, 0, serializer<CallId>())
                        CompositeDecoder.DECODE_DONE -> break
                        else -> error("Unexpected index: $index")
                    }
                }
                mapper(callId!!)
            }
        }
    }
}

inline fun <T, NewT> RpcQ<T>.map(mapper: (T) -> NewT): RpcQ<NewT> = when (this) {
    is RpcQ.Call -> RpcQ.Call(callId, mapper(argument))
    is RpcQ.Cancellation,
    is RpcQ.Confirmation -> this
}

val RpcQ<*>.callId: CallId get() = when (this) {
    is RpcQ.Call -> callId
    is RpcQ.Cancellation -> callId
    is RpcQ.Confirmation -> callId
}

@Serializable sealed class RpcR<out R> {
    @Serializable data class Answer<R>(val callId: CallId, val answer: R) : RpcR<R>()
    @Serializable data class Exception(val callId: CallId) : RpcR<Nothing>()
}

val RpcR<*>.callId: CallId get() = when (this) {
    is RpcR.Answer -> callId
    is RpcR.Exception -> callId
}

@Serializable sealed class Either<out L, out R> {
    @Serializable data class Left<L>(val value: L) : Either<L, Nothing>()
    @Serializable data class Right<R>(val value: R) : Either<Nothing, R>()
}

inline fun <OldL, NewL, R> Either<OldL, R>.mapLeft(mapper: (OldL) -> NewL): Either<NewL, R> = when (this) {
    is Either.Left -> Either.Left(mapper(value))
    is Either.Right -> this
}

class ExceptionThrownAcrossRpcBorder(): Exception("An exception was thrown across an RPC border. All information is lost on this side", null, false, false)

@JvmName("unwrapWithLeftovers")
fun <T, LLeftover, F, R, RNext, RLeftover> (
    (Flow<Either<RpcQ<Pair<T, F>>, LLeftover>>) -> Flow<Either<RpcR<R>, Either<RpcQ<RNext>, RLeftover>>>
).unwrapFromRpcFlowsApiToMoreFunctionalApi(
): (Flow<LLeftover>) -> Flow<Either<RpcQ<Pair<RNext, suspend (T, parentCallId: CallId?, F) -> R>>, RLeftover>> = { leftLeftovers ->
    flow {
        coroutineScope {
            val callMap = ConcurrentHashMap<Long, CompletableDeferred<RpcR<R>>>()
            val queries = Channel<RpcQ<Pair<T, F>>>()
            invoke(
                channelFlow {
                    launch { leftLeftovers.collect { send(Either.Right(it)) } }
                    queries.consumeEach { send(Either.Left(it)) }
                },
            ).collect { rpcEmission ->
                when (rpcEmission) {
                    is Either.Left -> callMap.remove(rpcEmission.value.callId.id)?.complete(rpcEmission.value)
                    is Either.Right -> emit(
                        rpcEmission.value.mapLeft { nextQuery ->
                            nextQuery.mapToFunctionalRpcQThatMapsToRpcFlows(
                                scopeThatCancellationsAreSentOn = this,
                                onFunctionQuery = queries::send,
                                mapThatAnswersWillBeWrittenInto = callMap,
                            )
                        },
                    )
                }
            }
        }
    }
}

@JvmName("unwrapSecondLast")
fun <T, TLeftover, F, R, RNext> (
    (Flow<Either<RpcR<R>, RpcQ<RNext>>>) -> Flow<Either<RpcQ<Pair<T, F>>, TLeftover>>
).unwrapFromRpcFlowsApiToMoreFunctionalApi(
): (Flow<RpcQ<Pair<RNext, suspend (T, parentCallId: CallId?, F) -> R>>>) -> Flow<TLeftover> = { queryFlow ->
    flow {
        coroutineScope {
            val callMap = ConcurrentHashMap<Long, Job>()
            val lambdaMap = ConcurrentHashMap<CallId, suspend (T, parentCallId: CallId?, F) -> R>()
            val responses = Channel<RpcR<R>>()
            invoke(
                channelFlow {
                    launch { responses.consumeEach { send(Either.Left(it)) } }
                    queryFlow.collect { query ->
                        send(Either.Right(query.map { it.first })) // Forward RNext calls up to the inner wrapper call.
                        lambdaMap.writeFromQuery(query)
                    }
                }
            ).collect { queryOrLeftover ->
                when (queryOrLeftover) {
                    is Either.Left -> queryOrLeftover.value.applyQueryToFunction(
                        functionToApplyQueryTo = lambdaMap[queryOrLeftover.value.callId.parentCallId?.parentCallId!!]!!,
                        scopeToLaunchCallOn = this,
                        mapThatTracksRunningCalls = callMap,
                        onFunctionRunResult = responses::send,
                    )
                    is Either.Right -> emit(queryOrLeftover.value)
                }
            }
        }
    }
}

@JvmName("unwrapLast")
fun <T, F, R> (
    (Flow<RpcQ<Pair<T, F>>>) -> Flow<RpcR<R>>
).unwrapFromRpcFlowsApiToMoreFunctionalApi(
): suspend (T, parentCallId: CallId?, F) -> R = { t, parentCallId, f ->
    val callId = CallId(0, parentCallId /* Could this ever not be null? */)
    this(
        flow {
            try {
                emit(RpcQ.Call(callId, Pair(t, f)))
            } catch (t: CancellationException) {
                withImportantCleanup { // TODO: Is this necessary?
                    emit(RpcQ.Cancellation(callId))
                }
            }
        }
    ).first()
        .also { assert(it.callId == callId) { "Wait wut? $callId != ${it.callId}" } }
        .getOrThrowAsRpcException()
}

@JvmName("unwrapParentCallIdTwoOrders")
inline fun <T, T1, F, R, R1, NewF> (suspend (T, parentCallId: CallId?, suspend (T1, parentCallId: CallId?, F) -> R1) -> R).unwrapFromRpcFlowsApiToMoreFunctionalApi(
    crossinline map: context(CallId) (F) -> NewF,
): suspend (T, suspend CoroutineScope.(T1, NewF) -> R1) -> R = privateUnwrap(parentCallId = null, map)

@JvmName("unwrapParentCallIdTwoOrdersContextual")
context(parentCallId: CallId)
inline fun <T, T1, F, R, R1, NewF> (suspend (T, parentCallId: CallId?, suspend (T1, parentCallId: CallId?, F) -> R1) -> R).unwrapFromRpcFlowsApiToMoreFunctionalApi(
    crossinline map: context(CallId) (F) -> NewF,
): suspend (T, suspend CoroutineScope.(T1, NewF) -> R1) -> R = this.privateUnwrap(parentCallId, map)

@OptIn(ExperimentalAtomicApi::class)
@PublishedApi
internal inline fun <T, T1, F, R, R1, NewF> (suspend (T, parentCallId: CallId?, suspend (T1, parentCallId: CallId?, F) -> R1) -> R).privateUnwrap(
    parentCallId: CallId?,
    crossinline map: context(CallId) (F) -> NewF,
): suspend (T, suspend CoroutineScope.(T1, NewF) -> R1) -> R = { t, f ->
    this(t, CallId(callIdCounter.incrementAndFetch(), parentCallId), { t1, callId, f1 ->
        coroutineScope { f(t1, map(callId!!, f1)) }
    })
}

inline fun <T, T1, F, R, R1, NewF> (suspend (T, suspend CoroutineScope.(T1, F) -> R1) -> R).removingClientSideCoroutineScopeReceivers(
    crossinline map: (F) -> NewF,
): suspend (T, suspend (T1, NewF) -> R1) -> R = { t, f ->
    this(t) { t1, f1 -> f(t1, map(f1)) }
}

@JvmName("addingServerSideCoroutineScopeReceivers")
inline fun <T, T1, F, R, R1, NewF> (suspend (T, suspend (T1, F) -> R1) -> R).addingServerSideCoroutineScopeReceivers(
    crossinline map: (F) -> NewF,
): suspend CoroutineScope.(T, suspend (T1, NewF) -> R1) -> R = { t, f ->
    this@addingServerSideCoroutineScopeReceivers(t) { t1, f1 -> f(t1, map(f1)) }
}

fun <T, F, R> (suspend (T, F) -> R).addingServerSideCoroutineScopeReceivers(): suspend CoroutineScope.(T, F) -> R = { t, f ->
    this@addingServerSideCoroutineScopeReceivers(t, f)
}

@OptIn(ExperimentalAtomicApi::class)
@JvmName("unwrapParentCallIdLast")
context(parentCallId: CallId)
fun <T, U, R> (suspend (T, parentCallId: CallId?, U) -> R).unwrapFromRpcFlowsApiToMoreFunctionalApi(): suspend (T, U) -> R = { t, u ->
    this(t, CallId(callIdCounter.incrementAndFetch(), parentCallId), u)
}

@JvmName("wrapWithLeftovers")
fun <T, LLeftover, F, R, RNext, RLeftover> (
    (Flow<LLeftover>) -> Flow<Either<RpcQ<Pair<RNext, suspend (T, parentCallId: CallId?, F) -> R>>, RLeftover>>
).wrapFromMoreFunctionalApiToMoreRpcFlowsApi(
): (Flow<Either<RpcQ<Pair<T, F>>, LLeftover>>) -> Flow<Either<RpcR<R>, Either<RpcQ<RNext>, RLeftover>>> = { queryFlow ->
    channelFlow {
        val callMap = ConcurrentHashMap<Long, Job>()
        val lambdaMap = ConcurrentHashMap<CallId, suspend (T, parentCallId: CallId?, F) -> R>()
        invoke(
            flow {
                queryFlow.collect { queryOrLeftover ->
                    when (queryOrLeftover) {
                        is Either.Left -> queryOrLeftover.value.applyQueryToFunction(
                            functionToApplyQueryTo = lambdaMap[queryOrLeftover.value.callId.parentCallId!!.parentCallId!!]!!,
                            scopeToLaunchCallOn = this@channelFlow,
                            mapThatTracksRunningCalls = callMap,
                            onFunctionRunResult = { send(Either.Left(it)) },
                        )
                        is Either.Right -> emit(queryOrLeftover.value)
                    }
                }
            },
        ).collect { queryOrRLeftover ->
            when (queryOrRLeftover) {
                is Either.Left -> {
                    send(Either.Right(Either.Left(queryOrRLeftover.value.map { it.first })))
                    lambdaMap.writeFromQuery(queryOrRLeftover.value)
                }
                is Either.Right -> send(Either.Right(queryOrRLeftover))
            }
        }
    }
}

private fun <F : Any, A> ConcurrentHashMap<CallId, F>.writeFromQuery(query: RpcQ<Pair<A, F>>) {
    when (query) {
        is RpcQ.Call -> this[query.callId] = query.argument.second
        is RpcQ.Cancellation -> remove(query.callId)
        is RpcQ.Confirmation -> remove(query.callId)
    }
}

@JvmName("wrapSecond")
fun <T, TLeftover, F, R, RNext> (
    (Flow<RpcQ<Pair<RNext, suspend (T, parentCallId: CallId?, F) -> R>>>) -> Flow<TLeftover>
).wrapFromMoreFunctionalApiToMoreRpcFlowsApi(
): (Flow<Either<RpcR<R>, RpcQ<RNext>>>) -> Flow<Either<RpcQ<Pair<T, F>>, TLeftover>> = { replyFlow ->
    channelFlow {
        val callMap = ConcurrentHashMap<Long, CompletableDeferred<RpcR<R>>>()
        val queries = Channel<RpcQ<Pair<T, F>>>()
        launch { queries.consumeEach { send(Either.Left(it)) } }
        invoke(
            channelFlow {
                replyFlow.collect { rpcEmission ->
                    when (rpcEmission) {
                        is Either.Left -> callMap.remove(rpcEmission.value.callId.id)?.complete(rpcEmission.value)
                        is Either.Right -> send(
                            rpcEmission.value.mapToFunctionalRpcQThatMapsToRpcFlows(
                                onFunctionQuery = { send(Either.Left(it)) },
                                mapThatAnswersWillBeWrittenInto = callMap,
                                scopeThatCancellationsAreSentOn = this,
                            )
                        )
                    }
                }
            }
        ).collect {
            send(Either.Right(it))
        }
    }
}


@JvmName("wrapFirst")
fun <T, F, R> (suspend (T, parentCallId: CallId?, F) -> R).wrapFromMoreFunctionalApiToMoreRpcFlowsApi(
): (Flow<RpcQ<Pair<T, F>>>) -> Flow<RpcR<R>> = { queries ->
    channelFlow {
        val callMap = ConcurrentHashMap<Long, Job>()
        queries.collect { query ->
            query.applyQueryToFunction(
                functionToApplyQueryTo = this@wrapFromMoreFunctionalApiToMoreRpcFlowsApi,
                scopeToLaunchCallOn = this,
                mapThatTracksRunningCalls = callMap,
                onFunctionRunResult = { send(it) },
            )
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
@JvmName("wrapParentCallIdTwoOrders")
inline fun <T, T1, F, R, R1, NewF> (suspend CoroutineScope.(T, suspend (T1, F) -> R1) -> R).wrapFromMoreFunctionalApiToMoreRpcFlowsApi(
    crossinline map: (F) -> NewF,
): suspend (T, parentCallId: CallId?, suspend (T1, parentCallId: CallId?, NewF) -> R1) -> R = { t, parentCallId, f ->
    coroutineScope {
        this@wrapFromMoreFunctionalApiToMoreRpcFlowsApi(t) { t1, f1 ->
            f(t1, CallId(callIdCounter.incrementAndFetch(), parentCallId), map(f1))
        }
    }
}



@JvmName("wrapParentCallIdFirst")
fun <T, U, R> (suspend CoroutineScope.(T, U) -> R).wrapFromMoreFunctionalApiToMoreRpcFlowsApi(): suspend (T, parentCallId: CallId?, U) -> R =
    { t, _, u -> coroutineScope { this@wrapFromMoreFunctionalApiToMoreRpcFlowsApi(t, u) } }

private fun <F, R, T> RpcQ<Pair<T, F>>.applyQueryToFunction(
    functionToApplyQueryTo: suspend (T, CallId?, F) -> R,
    scopeToLaunchCallOn: CoroutineScope,
    mapThatTracksRunningCalls: ConcurrentHashMap<Long, Job>,
    onFunctionRunResult: suspend (RpcR<R>) -> Unit,
) {
    when (this) {
        is RpcQ.Call -> mapThatTracksRunningCalls[callId.id] = scopeToLaunchCallOn.launch {
            try {
                onFunctionRunResult(
                    RpcR.Answer(callId, coroutineScope { functionToApplyQueryTo(argument.first, callId, argument.second) }),
                )
            } catch (t: Throwable) {
//                @OptIn(InternalCoroutinesApi::class)
//                handleCoroutineException(currentCoroutineContext(), t)
                onFunctionRunResult(RpcR.Exception(callId))
            } finally {
                mapThatTracksRunningCalls.remove(callId.id)
            }
        }
        is RpcQ.Cancellation -> mapThatTracksRunningCalls.remove(callId.id)?.cancel()
        is RpcQ.Confirmation -> mapThatTracksRunningCalls.remove(callId.id)?.cancel()
    }
}

@OptIn(ExperimentalAtomicApi::class)
private fun <F, R, RNext, T> RpcQ<RNext>.mapToFunctionalRpcQThatMapsToRpcFlows(
    scopeThatCancellationsAreSentOn: CoroutineScope,
    onFunctionQuery: suspend (RpcQ<Pair<T, F>>) -> Unit,
    mapThatAnswersWillBeWrittenInto: ConcurrentHashMap<Long, CompletableDeferred<RpcR<R>>>,
): RpcQ<Pair<RNext, suspend (T, parentCallId: CallId?, F) -> R>> = map { rNext ->
    val function: suspend (T, CallId?, F) -> R = { t: T, parentCallId: CallId?, u: F ->
        val callId = CallId(callIdCounter.incrementAndFetch(), parentCallId)
        val resultDeferred = mapThatAnswersWillBeWrittenInto.computeIfAbsent(callId.id) { CompletableDeferred() }
        onFunctionQuery(RpcQ.Call(callId, Pair(t, u)))
        try {
            resultDeferred.await().getOrThrowAsRpcException().also {
                onFunctionQuery(RpcQ.Confirmation(callId))
            }
        } catch (e: CancellationException) {
            mapThatAnswersWillBeWrittenInto.remove(callId.id)
            withContext(NonCancellable) {
                scopeThatCancellationsAreSentOn.async {
                    onFunctionQuery(RpcQ.Cancellation(callId))
                }.await()
            }
            throw e
        }
    }
    Pair(
        rNext,
        function,
    )
}

private fun <R> RpcR<R>.getOrThrowAsRpcException(): R = when (this) {
    is RpcR.Answer -> answer
    is RpcR.Exception -> throw ExceptionThrownAcrossRpcBorder()
}
