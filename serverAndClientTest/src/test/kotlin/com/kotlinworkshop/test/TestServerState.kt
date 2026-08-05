package com.kotlinworkshop.test

import kmpworkshop.common.WorkshopStage
import workshop.adminaccess.PuzzleState
import workshop.adminaccess.ServerState
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
internal fun serverStateThatOpened(stage: WorkshopStage): ServerState = ServerState(
    currentStage = stage,
    puzzleStates = mapOf(
        stage.stageName() to PuzzleState.Opened(Clock.System.now(), emptyMap()),
    ),
)

private fun WorkshopStage.stageName(): String = when (this) {
    WorkshopStage.Registration -> "Registration"
    is WorkshopStage.KotlinBasicsPuzzleStage -> name
    is WorkshopStage.CoroutinePuzzleStage -> name
}
