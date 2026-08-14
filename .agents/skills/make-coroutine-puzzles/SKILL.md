---
name: make-coroutine-puzzles
description: Create, extend, review, and test server-driven coroutine puzzles in this Kotlin Multiplatform workshop. Use when adding or changing CoroutinePuzzleStage entries, typed puzzle endpoints, coroutinePuzzle expectation programs, progressive workshop stages, participant solution routing, failure messages, or randomized-dispatch puzzle tests.
---

# Make Coroutine Puzzles

Treat the evaluator as a temporal specification, not an example solution. Read `CoroutinePuzzles.kt`, the closest evaluator, and its tests before editing.

## Change Workflow

1. Find the stage and analogous puzzles with `rg -n "StageName|fun .*Puzzle" common server client serverAndClientTest`.
2. Read `server/src/main/kotlin/kmpworkshop/server/CoroutinePuzzles.kt`, the analogous evaluator, and `serverAndClientTest/src/test/kotlin/com/kotlinworkshop/test/WorkshopCoroutinePuzzlesTest.kt`.
3. Route the stage through `WorkshopApiService.kt`, `CoroutinePuzzleType.kt`, and `runCoroutinePuzzleClient.kt`.
4. Write separate tests for the intended solution, previous solution, and common mistakes.
5. Run the focused tests through in-process, RPC-service, and real-RPC subclasses before broader tests.

| Concern | Location | Change when |
| --- | --- | --- |
| Stage enum and starter-file mapping | `common/.../WorkshopApiService.kt` | Adding a stage |
| Evaluator and route | `server/.../*CoroutinePuzzles.kt`, `CoroutinePuzzleType.kt` | Adding a stage |
| Participant route | `client/.../runCoroutinePuzzleClient.kt` | Adding a stage |
| API and endpoints | `workshopApi`, `common/.../CoroutinePuzzleEndpoint.kt` | The exercise needs a new visible operation |
| Hidden adaptation | `client` scaffolding and metadata | The evaluator needs instrumentation participants should not see |
| Editable answer | `workshopSolutions` | Adding a new exercise family or starter file |

Vocabulary: an **ordinary call** is a non-Flow endpoint submission; **quiescence** means runnable work on both protocol sides has settled; **unmatched** calls have no installed expectation; **protocol-visible** waiting involves a submitted call or expectation rather than only a local coroutine primitive. A **hidden** endpoint still participates in matching but is omitted from participant history.

## Reason About Protocol Turns

Calls batch at quiescence: ordinary calls started before either side settles are observed together. Use `awaitQuiescenceAndVerifyUnmatchedSubmissions` for exact simultaneous calls or absence.

| Need to observe | Pattern |
| --- | --- |
| Simultaneous or absent ordinary calls | `awaitQuiescenceAndVerifyUnmatchedSubmissions(...)` |
| Flow collection count/lifetime | Hidden ordinary lifetime endpoint |
| Call cancellation | Install `expectCanceledCall` before its trigger |

- Flow endpoints are filtered from unmatched submissions. Count collections or cancellation with a hidden ordinary lifetime endpoint instead.
- Local gates are protocol-invisible and can produce `FullyQuiescent`. Use structured concurrency; keep awaited facts protocol-visible.
- Install `expectCanceledCall { awaitCancellation() }` before the cancelling lifecycle event, emit that event synchronously, then join lexically.
- Try not to require `CoroutineStart.UNDISPATCHED` from the attendees unless it's a specific learning goal of the course.

## Model Hidden Upstream Lifetimes

Keep collection instrumentation out of participant APIs. Wrap each cold-source collection with one hidden call:

```kotlin
val tracked = flow { coroutineScope {
    val lifetime = launch { connectionLifetime.submitCall(Unit) }
    try { upstream.collect { emit(it) } }
    finally { lifetime.cancelAndJoin() }
} }
```

Register the endpoint with `isHiddenInHistory = true` in `ClientMetadata.kt`. Expect its cancellation in a sibling `launch` inside the `coroutineScope` that evaluates the collection; leaving that scope joins the expectation. Never give the wrapper scope to `shareIn`: its long-lived sharing job can block resource completion. Sharing belongs to the participant scope.

```kotlin
coroutineScope {
    launch { connectionLifetime.expectCanceledCall { awaitCancellation() } }
    evaluateCollection()
} // joins the cancellation expectation
```

## Specify Hot-Flow Lessons Observably

Drive lifecycle through domain flows, not exposed harness controls.

- Visibility: emit `false`, verify no lifetime, then emit `true`; this distinguishes observing the Boolean from honoring it.
- Sharing: activate both consumers, then count lifetime calls.
- Replay: emit before activating the late consumer, then require its ordinary update call at quiescence.
- Lazy startup: keep consumers inactive and require no lifetime.
- While subscribed: install cancellation expectation, deactivate the last consumers, await quiescence, then require that expectation to be complete.

If collectors can match either evaluator branch, emit identical values; do not invent consumer identity from arrival order.

## Design Progressive Stages and Errors

Introduce one observable property per stage. Keep each intended, previous-stage, and mistake case in its own test method. At deliberate quiescence checks, describe observed behavior and required behavior; avoid naming the exact fix. Accept generic protocol failures only when no reliable observation exists.

## Verify Without Encoding One Scheduler

For randomized failures, reproduce one seed by temporarily narrowing `runTestWithRandomizedDispatchOrdering`, then restore `0L until 100L`. Distinguish protocol deadlock from the test's wall-clock timeout. If assertion rendering throws `MetadataNotFoundException`, inspect `CoroutinePuzzleResultWithHistory.result` before rendering; the solution often unexpectedly succeeded or returned another type.

Run a named case across all three transports with:

```shell
./gradlew :serverAndClientTest:test --tests '*late ETA card needs replay*' --no-daemon
```

Run the whole module with `./gradlew :serverAndClientTest:test --no-daemon`, then run `git diff --check`.
