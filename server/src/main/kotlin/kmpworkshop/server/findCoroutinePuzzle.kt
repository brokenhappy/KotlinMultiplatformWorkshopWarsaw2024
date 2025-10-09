package kmpworkshop.server

import kmpworkshop.common.CoroutinePuzzle
import kmpworkshop.common.WorkshopStage

fun findCoroutinePuzzleFor(stage: WorkshopStage): CoroutinePuzzle? = when (stage) {
    WorkshopStage.Registration,
    WorkshopStage.PalindromeCheckTask,
    WorkshopStage.FindMinimumAgeOfUserTask,
    WorkshopStage.FindOldestUserTask,
    WorkshopStage.SliderGameStage,
    WorkshopStage.PressiveGameStage,
    WorkshopStage.DiscoGame -> null
    WorkshopStage.SumOfTwoIntsSlow -> simpleSumPuzzle()
    WorkshopStage.SumOfTwoIntsFast -> timedSumPuzzle()
    WorkshopStage.SimpleFlow -> simpleFlowPuzzle()
    WorkshopStage.CollectLatest -> collectLatestPuzzle()
}
