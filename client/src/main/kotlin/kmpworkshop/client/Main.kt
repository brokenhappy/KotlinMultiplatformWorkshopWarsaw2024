package kmpworkshop.client

import kmpworkshop.common.ApiKey
import kmpworkshop.common.CoroutinePuzzleSolutionResult
import kmpworkshop.common.GetNumberAndSubmit
import kmpworkshop.common.NumberFlowAndSubmit
import kmpworkshop.common.UserDatabase
import kmpworkshop.common.UserDatabaseWithLegacyQueryUser
import kmpworkshop.common.WorkshopServer
import kmpworkshop.common.WorkshopStage
import kmpworkshop.common.WorkshopStage.*
import kmpworkshop.common.asServer
import kmpworkshop.common.clientApiKey
import kmpworkshop.common.getNumberAndSubmit
import kmpworkshop.common.getUserDatabase
import kmpworkshop.common.getUserDatabaseWithLegacyQueryUser
import kmpworkshop.common.mapFromLegacyApiWithScaffolding
import kmpworkshop.common.numberFlowAndSubmit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.coroutineScope
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
            SimpleFlow,
            CollectLatest -> runCoroutinePuzzleClient(
                server,
                stage,
                bigScope = GlobalScope,
                sumSolution = { numberSummer(it) },
                collectSolution = { showingHowItsFlowing(it) },
                maximumAgeFindingTheSecondCoroutineSolution = { maximumAgeFindingWithCoroutines(it) },
                mappingLegacyApiCoroutineSolution = { mapFromLegacyApi(it) },
            )
        }
    } finally {
        println("\u001B[92mTHIS IS THE \u001B[91mEND\u001B[0m\u001B[92m OF THE APP OUTPUT ########################################################################################################\u001B[0m")
    }
}

suspend fun runCoroutinePuzzleClient(
    workshopServer: WorkshopServer,
    stage: WorkshopStage,
    bigScope: CoroutineScope,
    sumSolution: suspend CoroutineScope.(GetNumberAndSubmit) -> Unit,
    collectSolution: suspend CoroutineScope.(NumberFlowAndSubmit) -> Unit,
    maximumAgeFindingTheSecondCoroutineSolution: suspend CoroutineScope.(UserDatabase) -> Unit,
    mappingLegacyApiCoroutineSolution: suspend CoroutineScope.(UserDatabaseWithLegacyQueryUser) -> Unit,
): CoroutinePuzzleSolutionResult = when (stage) {
    Registration,
    PalindromeCheckTask,
    FindMinimumAgeOfUserTask,
    FindOldestUserTask -> error("Should never happen!")
    SumOfTwoIntsSlow,
    SumOfTwoIntsFast -> checkCoroutinePuzzle(
        workshopServer,
        stage.name,
        solution = { coroutineScope { sumSolution(it) } },
    ) { getNumberAndSubmit() }
    FindMaximumAgeCoroutines,
    FastFindMaximumAgeCoroutines -> checkCoroutinePuzzle(
        workshopServer,
        stage.name,
        solution = { coroutineScope { maximumAgeFindingTheSecondCoroutineSolution(it) } },
    ) { getUserDatabase() }
    MappingFromLegacyApisStepOne -> checkCoroutinePuzzle(
        workshopServer,
        stage.name,
        solution = { coroutineScope { mapFromLegacyApi(it) } },
    ) { getUserDatabaseWithLegacyQueryUser(bigScope) }
    MappingFromLegacyApisStepTwo,
    MappingFromLegacyApisStepThree -> checkCoroutinePuzzleInternal(
        workshopServer,
        stage.name,
        solution = { mapFromLegacyApiWithScaffolding { coroutineScope { mappingLegacyApiCoroutineSolution(it) } } },
    )
    SimpleFlow,
    CollectLatest -> checkCoroutinePuzzle(
        workshopServer,
        stage.name,
        solution = { coroutineScope { collectSolution(it) } },
    ) { numberFlowAndSubmit() }
}
