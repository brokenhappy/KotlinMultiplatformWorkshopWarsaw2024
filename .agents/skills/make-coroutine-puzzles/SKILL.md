---
name: make-coroutine-puzzles
description: Create, extend, review, and test server-driven coroutine puzzles in this Kotlin Multiplatform workshop. Use when adding or changing CoroutinePuzzleStage entries, typed puzzle endpoints, coroutinePuzzle expectation programs, progressive workshop stages, participant solution routing, failure messages, or randomized-dispatch puzzle tests.
---

# Make Coroutine Puzzles

Treat the evaluator as a temporal specification, not an example solution. Read `CoroutinePuzzles.kt`, the closest evaluator using the same protocol primitive, and its tests before editing.

## Change Workflow

1. Find analogous stages with `rg -n "StageName|fun .*Puzzle" common server client serverAndClientTest`.
2. Read `server/src/main/kotlin/kmpworkshop/server/CoroutinePuzzles.kt`, the analogous evaluator, and `serverAndClientTest/src/test/kotlin/com/kotlinworkshop/test/WorkshopCoroutinePuzzlesTest.kt`.
3. Implement and route the stage across the necessary modules.
4. Add separate tests for the intended solution, previous solution, and common mistakes.
5. Exercise every failure path and inspect its attendee-facing message.
6. Run focused tests through in-process, RPC-service, and real-RPC transports before broader tests.

| Concern | Location | Change when |
| --- | --- | --- |
| Stage enum and starter-file mapping | `common/.../WorkshopApiService.kt` | Adding a stage |
| Evaluator and route | `server/.../*CoroutinePuzzles.kt`, `CoroutinePuzzleType.kt` | Adding a stage |
| Participant route | `client/.../runCoroutinePuzzleClient.kt` | Adding a stage |
| API and endpoints | `workshopApi`, `common/.../CoroutinePuzzleEndpoint.kt` | Adding a participant-visible operation |
| Hidden adaptation | `client` scaffolding and metadata | Observing behavior without exposing harness controls |
| Editable answer | `workshopSolutions` | Adding an exercise family or starter file |

## The Attendee Is King

Evaluate the puzzle through the attendee's eyes. They see the editable API, starter code, stage description, call history, and failure message—not the evaluator's hidden machinery. Never make correctness depend on understanding scaffolding, transport details, unusual coroutine start modes, or one scheduler interleaving unless that is explicitly the lesson.

Failure messages are a primary teaching surface, not finishing polish. For every intended failure:

- run the realistic wrong solution;
- inspect the rendered result as the attendee will see it;
- explain the behavior currently observed;
- describe the behavior required instead;
- leave the exact operator, argument, or code change for the attendee to discover.

Give enough information to reason forward without giving away the answer. Use a generic protocol failure only when the evaluator cannot reliably observe a more specific behavioral mismatch.

## Protocol Model

`coroutinePuzzle { ... }` runs expectations while the participant submits typed endpoint calls. `expectCall` answers a matching call and returns its argument. Its producer's exception reaches both sides; across RPC only `ExpectedCallException` messages survive intact. Participant cancellation throws a cancellation exception into the expectation.

Calls batch at quiescence: ordinary calls started before either side settles are observed together. `awaitQuiescenceAndGetUnmatchedSubmissions()` leaves submissions suspended and resumes only the evaluator.

Vocabulary:

- An **ordinary call** is a non-Flow endpoint submission.
- An **unmatched call** has no installed expectation.
- **Protocol-visible** waiting involves a submitted call or expectation, not only a local coroutine primitive.
- A **hidden endpoint** participates in matching but is omitted from attendee history.

Use `awaitQuiescenceAndVerifyUnmatchedSubmissions(...)` for exact simultaneous ordinary calls or absence. Flow endpoints are filtered from unmatched submissions; observe Flow collection count or lifetime with a hidden ordinary endpoint instead.

## Learnings from previous agents:

### Avoid Quiescence and Cancellation Traps

- A local `CompletableDeferred.await()` is invisible to the protocol. If both sides wait only on local gates, the actor may validly return `FullyQuiescent`. Prefer structured concurrency and keep the next required fact protocol-visible.
- Install `expectCanceledCall { awaitCancellation() }` before triggering its cancellation. Trigger the event synchronously, then let the enclosing `coroutineScope` join the expectation.
- Use `CompletableDeferred` only when a fact must cross genuinely independent lifetimes, not to impose evaluator ordering.
- Do not require `CoroutineStart.UNDISPATCHED` from attendees unless it is the lesson. If an ordinary solution needs it to pass, the evaluator probably encodes a scheduler race.
- When concurrent identical submissions can match either evaluator branch, do not infer identity from arrival order. Make branches interchangeable or carry identity in the protocol.

When relevant work has no participant-visible call, instrument its lifetime in scaffolding with a hidden ordinary endpoint: submit it when work starts and cancel it in `finally`. Register it with `isHiddenInHistory = true` in `ClientMetadata.kt`. Expect its cancellation in a sibling child before evaluating the work:

```kotlin
coroutineScope {
    launch { callLifetime.expectCanceledCall { awaitCancellation() } }
    evaluateWork()
}
```

Keep this instrumentation out of participant APIs and editable solutions.

## Things to consider

 - Before you're done. Look at the other tests and other puzzles. Make sure you're in line with them
 - Don't overuse `CompletableDeferred`s and other concurrency gates. Prefer to use puzzle APIs and lexically scoped structured concurrency.

## Design Progressive Stages

Introduce one observable property per stage. The new evaluator should accept the previous correct solution except for the newly introduced requirement. Add separate test methods proving:

- the intended solution succeeds;
- the immediately previous solution fails for the new reason;
- each likely mistake reaches its intended message;
- relevant values, cancellation, ordering, and extra-call constraints are enforced.

Do not accidentally accept extra endpoints. Use exact quiescence checks when concurrency or absence is the lesson. Do not use `delay` as evidence of ordering or cancellation, and do not modify the core actor merely to force one puzzle through it.

## Verify

Randomized dispatch tests must accept every valid interleaving. Reproduce a failure by narrowing only the failing test to `seed..seed`, then restore `0L until 100L`. Distinguish protocol deadlock from wall-clock timeout.

If assertion rendering throws `MetadataNotFoundException`, inspect `CoroutinePuzzleResultWithHistory.result` before rendering; the solution often unexpectedly succeeded or returned another result type.

Run a focused case across all transports, then the affected suite:

```shell
./gradlew :serverAndClientTest:test --tests '*descriptive test name*' --no-daemon
./gradlew :serverAndClientTest:test --no-daemon
```

Run `git diff --check`, keep endpoint serializers valid, and preserve unrelated worktree changes.
