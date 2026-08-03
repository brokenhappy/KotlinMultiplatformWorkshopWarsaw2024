package kmpworkshop.client

import kmpworkshop.common.ApiKey
import kmpworkshop.common.WorkshopStage.*
import kmpworkshop.common.asServer
import kmpworkshop.common.clientApiKey
import kotlinx.coroutines.flow.first

suspend fun main() {
    try {
        println("\u001B[92mTHIS IS THE START OF THE APP OUTPUT ########################################################################################################\u001B[0m")
        val server = workshopService.asServer(ApiKey(clientApiKey ?: error("You need to finish registration first!")))
        when (val stage = server.currentStage().first()) {
            Registration -> println("We are in the Registration stage. Please run `Registration` configuration instead!")
            PalindromeCheckTask -> checkCodePuzzle(stage.name, solution = ::doPalindromeCheckOn)
            FindMinimumAgeOfUserTask -> checkCodePuzzle(stage.name, solution = ::serializableFindMinimumAgeOf)
            FindOldestUserTask -> checkCodePuzzle(stage.name, solution = ::serializableFindOldestUserAmong)
            SumOfTwoIntsSlow,
            SumOfTwoIntsFast,
            FindMaximumAgeCoroutines,
            FastFindMaximumAgeCoroutines,
            MappingFromLegacyApisStepOne,
            MappingFromLegacyApisStepTwo,
            MappingFromLegacyApisStepThree,
            MappingFromLegacyApisStepFour,
            ExceptionCatchingWithCoroutines,
            SimpleFlow,
            CollectLatest -> runCoroutinePuzzleClient(
                server,
                stage,
                sumSolution = { numberSummer(it) },
                collectSolution = { showingHowItsFlowing(it) },
                maximumAgeFindingTheSecondCoroutineSolution = { maximumAgeFindingWithCoroutines(it) },
                mappingLegacyApiCoroutineSolution = { mapFromLegacyApi(it) },
                exceptionHandlingSolution = { exceptionHandlingPuzzle(it) },
            )
        }
    } finally {
        println("\u001B[92mTHIS IS THE \u001B[91mEND\u001B[0m\u001B[92m OF THE APP OUTPUT ########################################################################################################\u001B[0m")
    }
}

