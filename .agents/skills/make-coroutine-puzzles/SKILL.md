---
name: make-coroutine-puzzles
description: Create, extend, review, and test server-driven coroutine puzzles in this Kotlin Multiplatform workshop. Use when adding or changing CoroutinePuzzleStage entries, typed puzzle endpoints, coroutinePuzzle expectation programs, progressive workshop stages, participant solution routing, failure messages, or randomized-dispatch puzzle tests.
---

# Make Coroutine Puzzles

Build puzzles with this repository's bidirectional expectation protocol. Do not substitute standalone snippets, predicted console output, or generic quiz text unless explicitly requested.

## Start from the Existing Architecture

Read the relevant files before editing:

1. Read `server/src/main/kotlin/kmpworkshop/server/CoroutinePuzzles.kt` for the builder API and matching semantics.
2. Read the closest existing evaluator in `server/src/main/kotlin/kmpworkshop/server/*CoroutinePuzzle*.kt`.
3. Read the participant-facing API or solution file named by the stage in `common/src/main/kotlin/kmpworkshop/common/WorkshopApiService.kt`.
4. Read related cases in `serverAndClientTest/src/test/kotlin/com/kotlinworkshop/test/WorkshopCoroutinePuzzlesTest.kt`.

## Protocol Model

`coroutinePuzzle { ... }` runs an expectation program on the server while the participant solution submits endpoint calls through the client protocol. Calls are auto-batched at quiescence. The state actor matches submissions and expectations by endpoint descriptor and returns structured failures for missing, extra, or unexpected calls.

`CoroutinePuzzleEndPoint<T, R>` describes participant argument `T` and result `R`. On the evaluator side, `endpoint.expectCall { argument: T -> result: R }` is the core operation: it answers the submitted call and returns its argument to the evaluator. Exceptions from its closure are thrown to both sides; across RPC, only `ExpectedCallException` messages are retained. It throws a cancellation exception when the participant cancels the submitted call.

Build on that operation with `endpoint.expectCall(result)`, `endpoint.expectThrowingCall(message)`, and `endpoint.expectCanceledCall { ... }`.

## Quiescence Alternation

The protocol alternates turns at quiescence. When currently runnable branches on both sides settle, the evaluator can inspect unmatched participant submissions while those participant calls remain suspended. The evaluator then installs matching expectations; answering them resumes the participant side, which can run until the next quiescent point.

Use this alternation to describe concurrency and ordering declaratively. Prefer lexical `coroutineScope` structure and child coroutines to coordinate work. Use `CompletableDeferred` or another custom gate only when a fact must cross otherwise independent lifetimes; do not use one just to impose an order that structured concurrency and quiescence already express.

## Quiescence and Concurrency

`awaitQuiescenceAndGetUnmatchedSubmissions()` is the core quiescence operation. It waits for quiescence and returns the unmatched endpoint calls.

`verifyUnmatchedSubmissions(...)` compares a supplied list with an expected multiset of endpoints. `awaitQuiescenceAndVerifyUnmatchedSubmissions(...)` composes both operations. Use the latter to require exact simultaneous calls, including repeated calls, or an empty list when no participant operation may have started yet.

## Design the Learning Progression

Choose one observable coroutine property per stage: sequential versus concurrent calls, structured lifetime, exception propagation, cancellation completion, or Flow cancellation. For multiple stages sharing one workshop file, make each evaluator accept the previous correct solution and reject it only when introducing the next lesson.

Express correctness as interactions with `CoroutinePuzzleEndPoint<T, R>` values:

- Use `expectCall(value)` or `expectCall { argument -> result }` for successful calls.
- Use `expectThrowingCall(message)` for an endpoint result that must throw.
- Use `expectCanceledCall { ... }` for work the participant must cancel.
- Use `coroutineScope` and child `launch` calls to install expectations that may occur concurrently.
- Use `verify` and `verifyNotNull` for values and domain constraints; return actionable messages from `CoroutinePuzzleErrorMessages`.

Treat the evaluator as a temporal specification, not as an example solution. Avoid encoding one scheduler interleaving when several are valid.

## Implement Across Modules

For a new participant-visible operation, add a typed endpoint in `common/src/main/kotlin/kmpworkshop/common/CoroutinePuzzleEndpoint.kt` and connect it through the participant-facing API/scaffolding.

For a new stage:

1. Add a uniquely named `CoroutinePuzzleStage` with its workshop Kotlin filename in `WorkshopApiService.kt`.
2. Add the evaluator under `server/src/main/kotlin/kmpworkshop/server/` using `coroutinePuzzle { ... }`.
3. Route the stage in `CoroutinePuzzleType.findPuzzleFor`.
4. Route it to the appropriate solution function in `client/.../runCoroutinePuzzleClient.kt`; extend `CoroutinePuzzleWorkshopSolutions` only for a genuinely new exercise family.
5. Add focused feedback in `CoroutinePuzzleErrorMessages.kt`.
6. Update participant API, scaffolding, or solution files only as required by the exercise contract.

Keep shared protocol types in `common`, evaluator behavior in `server`, participant orchestration in `client`, and editable workshop implementations in `workshopSolutions`.

## Vet Error Messages

Treat error messages as a core part of the learning experience. Near the end of making a puzzle, exercise every failure path and vet the message from the participant's perspective.

Make each message actionable and helpful for the workshop audience: identify the observed problem, connect it to the intended coroutine concept, and give enough direction to correct the implementation without supplying the complete solution. Use focused messages in `CoroutinePuzzleErrorMessages.kt` when structured protocol failures do not provide that guidance.

## Test the Contract

Tests are mandatory. Add each puzzle's coverage to `WorkshopCoroutinePuzzleTest` in `serverAndClientTest/src/test/kotlin/com/kotlinworkshop/test/WorkshopCoroutinePuzzlesTest.kt`; its in-process and RPC subclasses exercise the same contract through both transports.

Give the stage good coverage: prove the intended solution succeeds, prove the previous-stage or common mistaken solution fails for the intended reason, and cover the relevant values, cancellation, or ordering constraint. Add coverage for each specific error path and assert its intended message. The base test harness runs under virtual time with randomized dispatch order, so evaluator logic must work for every valid interleaving rather than one observed schedule.

Prefer assertions on `CoroutinePuzzleSolutionResult`, endpoint history, arguments, and returned values over wall-clock timing. Use the existing helpers such as `doConcurrentSumPuzzle`, `doCollectLatestPuzzle`, and `assertIsOk`/`assertIsNotOk` as patterns.

Run the narrowest relevant Gradle test first, then broaden to the module test suite when shared protocol or routing changes.

## Guardrails

- Do not modify the core actor in `CoroutinePuzzles.kt` merely to make one evaluator pass unless the protocol itself is defective.
- Do not use `delay` as proof of concurrency or ordering.
- Do not wait forever in an evaluator; pair deliberately suspended expectations with cancellation or lifetime teardown.
- Do not accept extra endpoint calls accidentally. Use quiescence checks when exact parallelism or absence matters.
- Do not expose random values in failure messages unless they help the participant correct the solution.
- Keep endpoint serializers valid: endpoint argument and result types must be serializable by the protocol.
- Keep the skill and implementation agent-agnostic; do not add vendor-specific metadata or instructions.
