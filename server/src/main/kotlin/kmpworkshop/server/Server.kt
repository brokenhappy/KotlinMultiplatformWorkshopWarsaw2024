@file:Suppress("ReplaceToWithInfixForm")

package kmpworkshop.server

import kmpworkshop.common.*
import kmpworkshop.common.DefaultApis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import workshop.adminaccess.*
import kotlin.random.Random
import kotlin.time.Clock

suspend fun main() {
    hostServer()
}

suspend fun hostServer(): Nothing = withContext(Dispatchers.Default) {
    val serverState = MutableStateFlow(ServerState())
    val eventBus = Channel<ScheduledWorkshopEvent>(capacity = Channel.UNLIMITED)
    val soundEvents = MutableSharedFlow<SoundPlayEvent>()
    val clientBugReports = MutableSharedFlow<StoredClientBugReport>(extraBufferCapacity = 8)
    val onSoundEvent: (SoundPlayEvent) -> Unit = {
        launch { soundEvents.emit(it) }
    }
    launch {
        serve(
            rpcService {
                workshopService(
                    serverState,
                    onEvent = { eventBus.trySend(it) },
                    clientBugReports = clientBugReports,
                )
            },
            rpcService {
                adminAccess(
                    serverState,
                    onEvent = { eventBus.trySend(it) },
                    sounds = soundEvents,
                    clientBugReports = clientBugReports,
                )
            },
        )
    }
    mainEventLoopWritingTo(
        serverState,
        eventBus,
        onSoundEvent = onSoundEvent,
        onEvent = { launch { eventBus.send(it) } },
    )
}

fun workshopService(
    serverState: Flow<ServerState>,
    onEvent: OnEvent,
    clientBugReports: MutableSharedFlow<StoredClientBugReport> = MutableSharedFlow(extraBufferCapacity = 8),
): WorkshopApiService = object : WorkshopApiService {
    override suspend fun registerApiKeyFor(name: String): ApiKeyRegistrationResult =
        onEvent.fire(RegistrationStartEvent(name, Random.nextLong()))

    override suspend fun verifyRegistration(key: ApiKey): NameVerificationResult =
        onEvent.fire(RegistrationVerificationEvent(key))

    override suspend fun submitClientBugReport(
        key: ApiKey,
        report: ClientBugReport,
    ): ClientBugReportSubmissionResult = submitClientBugReport(
        key,
        report,
        serverState.first(),
        clientBugReports,
    )

    override fun currentStage(): Flow<WorkshopStage> = serverState.map { it.currentStage }

    override fun doKotlinBasicsPuzzleSolveAttempt(
        key: ApiKey,
        puzzleId: String,
        answers: Flow<JsonElement>,
    ): Flow<SolvingStatus> =
        context(KotlinBasicsPuzzleType) { doPuzzleAttempt(key, puzzleId, answers, serverState, onEvent) }

    override fun doCoroutinePuzzleSolveAttempt(
        key: ApiKey,
        puzzleId: String,
        clientMetadataHash: String,
        messages: Flow<List<WithCallId<CoroutinePuzzleSubmissionPayload>>>
    ): Flow<CoroutinePuzzleExpectationBatchOrCompletion> =
        if (clientMetadataHash != DefaultApis.endpointHash())
            flow { emit(CoroutinePuzzleType.customError("Client and server coroutine puzzle APIs do not match.")) }
        else context(CoroutinePuzzleType) { doPuzzleAttempt(key, puzzleId, messages, serverState, onEvent) }
}

/** Shit, I couldn't help myself from introducing a type class, just inline this and the type if it needs a lot of maintanence */
context(type: PuzzleType<Stage, Outgoing, Incoming>)
private fun <Stage: Enum<Stage>, Outgoing, Incoming> doPuzzleAttempt(
    key: ApiKey,
    puzzleId: String,
    answers: Flow<Incoming>,
    serverState: Flow<ServerState>,
    onEvent: OnEvent,
): Flow<Outgoing> {
    val puzzle = type
        .enumEntries()
        .firstOrNull { it.name == puzzleId }
        ?.let { type.findPuzzleFor(it) }
        ?: return flow {
            println("Someone tried to request puzzle name: $puzzleId")
            emit(type.customError(accidentalChangesMadeMessage))
        }

    return channelFlow {
        if (serverState.first().participants.none { it.apiKey == key }) {
            send(type.customError(participantNotActiveMessage))
            return@channelFlow
        }

        try {
            puzzle.use { (outgoing, incoming) ->
                launch {
                    try {
                        answers.collect { incoming.send(it) }
                    } finally {
                        incoming.close()
                    }
                }
                for (message in outgoing) {
                    send(
                        if (!message.isSuccessfulCompletion()) message
                        else when (onEvent.fire(PuzzleFinishedEvent(Clock.System.now(), key, puzzleId))) {
                            PuzzleCompletionResult.Done -> message
                            PuzzleCompletionResult.AlreadySolved -> type.customError(alreadySolvedMessage)
                            PuzzleCompletionResult.PuzzleNotOpenedYet -> type.customError(puzzleNotOpenedYetMessage)
                            PuzzleCompletionResult.NotActiveParticipant -> type.customError(participantNotActiveMessage)
                        }
                    )
                }
            }
        } catch (_: MetadataNotFoundException) {
            send(type.customError(metadataNotFoundMessage))
        }
    }
}

interface PuzzleType<Stage : Enum<Stage>, Outgoing, Incoming> {
    fun customError(message: String): Outgoing
    fun enumEntries(): List<Stage>
    fun isSuccessfulCompletion(outgoing: Outgoing): Boolean
    fun findPuzzleFor(stage: Stage): Resource<CommunicationProtocol<Outgoing, Incoming>>
}

context(type: PuzzleType<*, Outgoing, *>)
fun <Outgoing> Outgoing.isSuccessfulCompletion(): Boolean = type.isSuccessfulCompletion(this)

private val alreadySolvedMessage = """
    Yaay! You solved it again! Perhaps you could look around and see if some of your peers would like your help? :))
""".trimIndent()

private val puzzleNotOpenedYetMessage = """
    Hold on there pal! Don't get ahead of yourself, the puzzle is not yet open for solving!
    I'm sure there's people around you that you can help :))
""".trimIndent()

private val metadataNotFoundMessage = """
    Tried to call endpoint that does not exist. The server and client are out of sync.
    Try updating the repository (pulling latest changes). Otherwise ask for workshop host for help.
""".trimIndent()

private val participantNotActiveMessage = """
    The participant you tried to register with is not active. That means one of:
      - The workshop host deactivated you temporarily for some reason.
        In this case, ask the workshop host that you are ready again, and that they can reactivate your registration.
      - Something went wrong with your registration, or the server rolled back to a state before you registered.
        In this case, please register again.
""".trimIndent()
