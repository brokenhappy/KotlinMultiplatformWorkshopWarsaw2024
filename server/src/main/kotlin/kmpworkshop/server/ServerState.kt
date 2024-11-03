package kmpworkshop.server

import kmpworkshop.common.ApiKey
import kmpworkshop.common.PressiveGamePressType
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
internal data class Participant(val name: String, val apiKey: ApiKey)

@Serializable
internal data class ServerState(
    val participants: List<Participant> = emptyList(),
    val deactivatedParticipants: List<Participant> = emptyList(),
    val unverifiedParticipants: List<Participant> = emptyList(),
    val currentStage: WorkshopStage = WorkshopStage.Registration,
    val scheduledEvents: List<TimedEvent> = emptyList(),
    val puzzleStates: Map<String, PuzzleState> = emptyMap(),
    val sliderGameState: SliderGameState = SliderGameState.NotStarted,
    val pressiveGameState: PressiveGameState = PressiveGameState.NotStarted,
)

@Serializable
internal data class TimedEvent(
    val time: Instant,
    val type: TimedEventType,
)

@Serializable
internal sealed class TimedEventType() {
    data object PressiveGameTickEvent: TimedEventType()
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
internal enum class WorkshopStage(val kotlinFile: String) {
    Registration("Registration.kt"), // TODO: Make possible to disable and enable a participant?
    PalindromeCheckTask("PalindromeCheck.kt"),
    FindMinimumAgeOfUserTask("MinimumAgeFinding.kt"),
    FindOldestUserTask("OldestUserFinding.kt"),
    SliderGameStage("SliderGameClient.kt"), // TODO: Handle deletion of user!
    PressiveGameStage("PressiveGameClient.kt"), // TODO: Remember to make it possible to exclude myself in case of odd number of participants!
}

@Serializable
internal sealed class PressiveGameState {
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
internal data class SecondPressiveGameParticipantState(
    val pairingState: PressivePairingState,
    val key: ApiKey,
    val personalId: String,
    val isBeingCalled: Boolean,
)
@Serializable
internal data class FirstPressiveGameParticipantState(
    val pressesLeft: List<PressiveGamePressType>,
    val justFailed: Boolean,
    val finishTime: Instant?, // TODO: Yikes, nullable? Refactor!
)

@Serializable
internal sealed class PressivePairingState {
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
internal sealed class SliderGameState {
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
