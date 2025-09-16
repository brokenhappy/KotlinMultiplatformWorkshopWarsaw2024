package kmpworkshop.client

import kmpworkshop.common.ApiKey
import kmpworkshop.common.SolvingStatus
import kmpworkshop.common.clientApiKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

internal inline fun <reified T : Any, reified R : Any> checkCodePuzzle(
    puzzleName: String,
    noinline solution: (T) -> R,
) = checkCodePuzzle(puzzleName, solution, serializer(), serializer())

internal fun <T : Any, R : Any> checkCodePuzzle(
    puzzleName: String,
    solution: (T) -> R,
    tSerializer: KSerializer<T>,
    rSerializer: KSerializer<R>,
) {
    try {
        runBlocking {
            val answers = MutableSharedFlow<R>()
            workshopService
                .doPuzzleSolveAttempt(
                    key = ApiKey(clientApiKey!!),
                    puzzleName = puzzleName,
                    answers = answers.map { Json.encodeToJsonElement(rSerializer, it) },
                )
                .collect { question ->
                    when (question) {
                        is SolvingStatus.Next -> {
                            val answer = try {
                                solution(Json.decodeFromJsonElement(tSerializer, question.questionJson))
                            } catch (_: SerializationException) {
                                accidentalChangesMadeError()
                            }
                            answers.emit(answer)
                        }
                        is SolvingStatus.Failed -> {
                            try {
                                val input = Json.decodeFromJsonElement(tSerializer, question.input)
                                val actual = Json.decodeFromJsonElement(rSerializer, question.actual)
                                val expected = Json.decodeFromJsonElement(rSerializer, question.expected)
                                throw AssertionError(
                                    """
                                        Tested your solution with input: $input
                                        But we got $actual instead of $expected!
                                    """.trimIndent()
                                )
                            } catch (_: SerializationException) {
                                accidentalChangesMadeError()
                            }
                        }
                        SolvingStatus.AlreadySolved -> {
                            println("""
                                Yaay! You solved it again! Perhaps you could look around and see if some of your peers would like your help? :))
                            """.trimIndent())
                            throw DoneWithPuzzleException()
                        }
                        SolvingStatus.InvalidApiKey -> wrongApiKeyConfigurationError()
                        SolvingStatus.PuzzleNotOpenedYet -> {
                            println("""
                                Hold on there pal! Don't get ahead of yourself, the puzzle is not yet open for solving!
                                I'm sure there's people around you that you can help :))
                            """.trimIndent())
                            throw DoneWithPuzzleException()
                        }
                        SolvingStatus.IncorrectInput -> accidentalChangesMadeError()
                        SolvingStatus.Done -> {
                            println("Yaaay! You solved the puzzle! You can now wait for the workshop host to tell you what to do next!")
                            throw DoneWithPuzzleException()
                        }
                    }
                }
        }
    } catch (_: DoneWithPuzzleException) {
    }
}

private class DoneWithPuzzleException: Throwable("")

private fun accidentalChangesMadeError(): Nothing =
    error("You accidentally made changes to the puzzle types or scaffolding.\nPlease revert those changes yourself or ask the workshop host for help!")