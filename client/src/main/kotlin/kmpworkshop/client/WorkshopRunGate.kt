package kmpworkshop.client

import kmpworkshop.common.WorkshopStage

/** Allows exactly one attempt for each stage and successful-reload generation. */
class WorkshopRunGate(initialStage: WorkshopStage) {
    var stage: WorkshopStage = initialStage
        private set
    var generation: Long = 0
        private set
    private val attempted = mutableSetOf<Pair<WorkshopStage, Long>>()

    val canRun: Boolean get() = stage to generation !in attempted

    fun enterStage(newStage: WorkshopStage) {
        stage = newStage
    }

    fun successfulReload() {
        generation++
    }

    fun startAttempt(): Boolean {
        if (!canRun) return false
        attempted += stage to generation
        return true
    }
}
