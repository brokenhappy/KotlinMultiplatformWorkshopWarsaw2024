package kmpworkshop.server

import kmpworkshop.common.*
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Participant(val name: String, val apiKey: ApiKey)

@Serializable
data class ServerState(
    val participants: List<Participant> = emptyList(),
    val deactivatedParticipants: List<Participant> = emptyList(),
    val unverifiedParticipants: List<Participant> = emptyList(),
    val currentStage: WorkshopStage = WorkshopStage.Registration,
    val settings: ServerSettings = ServerSettings(),
    val scheduledEvents: List<TimedEvent> = emptyList(),
    val puzzleStates: Map<String, PuzzleState> = emptyMap(),
    val sliderGameState: SliderGameState = SliderGameState.NotStarted,
    val pressiveGameState: PressiveGameState = PressiveGameState.NotStarted,
    val discoGameState: DiscoGameState = DiscoGameState.NotStarted,
)

@Serializable
data class TimedEvent(
    val time: Instant,
    val type: TimedEventType,
)

@Serializable
sealed class TimedEventType {
    @Serializable
    data object PressiveGameTickEvent: TimedEventType()
    @Serializable
    data class FirstDiscoGameTargetTickEvent(val randomSeed: Long): TimedEventType()
    @Serializable
    data class FirstDiscoGamePrivateTickEvent(val randomSeed: Long): TimedEventType()
    @Serializable
    data object SecondDiscoGameBackgroundTickEvent: TimedEventType()
    @Serializable
    data object SecondDiscoGamePressTimeoutEvent: TimedEventType()
    @Serializable
    data object PlaySuccessSound: TimedEventType()
    @Serializable
    data class PlayIncrementSound(val pitch: Double): TimedEventType()
    @Serializable
    data object PlayProgressLossSound: TimedEventType()
}

@Serializable
sealed class PuzzleState {
    @Serializable
    data object Unopened: PuzzleState()
    @Serializable
    data class Opened(val startTime: Instant, val submissions: Map<ApiKeyString, Instant>): PuzzleState()
}

typealias ApiKeyString = String // Because ApiKey is not serializable when used as a Map key

@Serializable
sealed class DiscoGameState {
    @Serializable
    data object NotStarted : DiscoGameState()
    @Serializable
    sealed class Second : DiscoGameState() {
        @Serializable
        data class InProgress(
            val orderedParticipants: List<SecondDiscoGameParticipantState>,
            val progress: Int,
            val instructionOrder: List<DiscoGameInstructionRequest>,
        ) : Second()
        @Serializable
        data object Done : Second()
    }
    @Serializable
    sealed class First : DiscoGameState() {
        @Serializable
        data class InProgress(
            val startTime: Instant,
            val states: Map<ApiKeyString, FirstDiscoGameParticipantState>,
            val target: ColorAndInstructionWithPrevious,
        ) : First()
        @Serializable
        data class Done(val submissions: Submissions) : First()
    }
}

@Serializable
data class SecondDiscoGameParticipantState(val participant: ApiKey, val color: SerializableColor)
@Serializable
sealed class FirstDiscoGameParticipantState {
    @Serializable
    data class InProgress(
        val colorAndInstructionState: ColorAndInstructionWithPrevious,
        val completionCount: Int,
    ) : FirstDiscoGameParticipantState()
    @Serializable
    data class Done(val finishTime: Instant) : FirstDiscoGameParticipantState()
}
@Serializable
data class ColorAndInstruction(val color: SerializableColor, val instruction: DiscoGameInstruction?)
@Serializable
data class ColorAndInstructionWithPrevious(val current: ColorAndInstruction, val previous: ColorAndInstruction)

@Serializable
data class DiscoGameInstructionRequest(val participant: ApiKey, val instruction: DiscoGameInstruction)

@Serializable
sealed class PressiveGameState {
    @Serializable
    data object NotStarted : PressiveGameState()
    @Serializable
    data class FirstGameInProgress(
        val startTime: Instant,
        val states: Map<ApiKeyString, FirstPressiveGameParticipantState>,
    ) : PressiveGameState()
    @Serializable
    data class FirstGameDone(
        val startTime: Instant,
        val finishTimes: Map<ApiKeyString, Instant>,
    ) : PressiveGameState()
    @Serializable
    data class SecondGameInProgress(
        val order: List<ApiKey>,
        val progress: Int = 0,
        val states: Map<ApiKeyString, SecondPressiveGameParticipantState>,
    ) : PressiveGameState()
    @Serializable
    data object SecondGameDone : PressiveGameState()
    @Serializable
    data class ThirdGameInProgress(
        val order: List<ApiKey>,
        val progress: Int = 0,
        /** Null means that a whole cycle was just done, and a new one is about to start */
        val participantThatIsBeingRung: ApiKey?,
    ) : PressiveGameState()
    @Serializable
    data object ThirdGameDone : PressiveGameState()
}

@Serializable
data class SecondPressiveGameParticipantState(
    val pairingState: PressivePairingState,
    val key: ApiKey,
    val personalId: String,
    val isBeingCalled: Boolean,
)

@Serializable
data class ServerSettings(
    /** Value is `in -1..1`. Negative means darker, Positive means lighter */
    val dimmingRatio: Float = 0f,
)

@Serializable
data class FirstPressiveGameParticipantState(
    val pressesLeft: List<PressiveGamePressType>,
    val justFailed: Boolean,
    val finishTime: Instant?, // TODO: Yikes, nullable? Refactor!
)

@Serializable
sealed class PressivePairingState {
    @Serializable
    data class Calling(val partner: ApiKey) : PressivePairingState()
    @Serializable
    data object TriedToCallNonExistingCode : PressivePairingState()
    @Serializable
    data object DialedThemselves : PressivePairingState()
    @Serializable
    data object DialedPersonIsBeingCalled : PressivePairingState()
    @Serializable
    data object DialedPersonIsCalling : PressivePairingState()
    @Serializable
    data object PartnerHungUp : PressivePairingState()
    @Serializable
    data class SuccessFullyPaired(val partner: ApiKey) : PressivePairingState()
    @Serializable
    data class RoundSuccess(val isPlacedBeforePartner: Boolean) : PressivePairingState()
    @Serializable
    data class InProgress(val progress: String) : PressivePairingState()
}

@Serializable
sealed class SliderGameState {
    @Serializable
    data object NotStarted : SliderGameState()
    @Serializable
    data class InProgress(
        val participantStates: Map<ApiKeyString, SliderState>,
        val pegLevel: Int,
        val pegPosition: Double,
    ) : SliderGameState()
    @Serializable
    data class Done(val lastState: InProgress) : SliderGameState()
}

@Serializable
data class SliderState(val gapOffset: Double, val position: Double)
