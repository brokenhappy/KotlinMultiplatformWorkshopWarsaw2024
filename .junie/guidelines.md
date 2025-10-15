# Kotlin Workshop – Development Guidelines

This document captures project-specific knowledge for building, running, testing, and extending this repository. It is meant for advanced developers; only non-trivial, project-tailored details are included.

Last verified on: 2025-10-15

## 1. Build and Configuration

This repo is a Gradle multi-module Kotlin project with a mix of JVM, Kotlin Multiplatform (KMP), Android, iOS, and Ktor server code.

- Gradle versioning/plugins are centralized in the root `build.gradle.kts`:
  - Kotlin multiplatform: 2.2.0 (apply false)
  - Kotlin JVM (for server and other JVM-only modules): 2.0.10 (apply false)
  - Android Gradle Plugin: 8.11.2 (apply false)
  - Ktor plugin: 3.3.0 (apply false)
  - KSP: 2.2.0-2.0.2
  - kotlinx.rpc plugin: 0.9.1
  - Compose Multiplatform plugin: 1.8.2
- Toolchains are resolved via Foojay resolver (see `settings.gradle.kts`). JVM toolchain for the server is set to 17.

### Modules
Defined in `settings.gradle.kts`:
- common – Shared KMP module with domain, serialization, and puzzle contracts.
- client – Multiplatform client (targets: androidMain, iosMain, jvmMain, nativeMain; commonMain contains shared client logic and puzzle scaffolding).
- server – Ktor Netty server exposing workshop endpoints and coroutine puzzle endpoints; depends on `common` and `serverAndAdminCommon`.
- adminClient – Admin utilities for interacting with the server (JVM/desktop Compose likely; see module Gradle file).
- serverAndAdminCommon – Shared contracts/utilities for server + adminClient.
- testEnvironment – Utilities / fixtures to simulate the environment.
- registration – Registration service/features used by the workshop flow.

### Build commands
- Build everything: `./gradlew build`
- Build without tests: `./gradlew assemble`
- Server only: `./gradlew :server:build`
- Client only (compile all targets): `./gradlew :client:build`
- iOS specifics: building iOS targets requires a macOS environment with Xcode toolchains. This repo includes `iosApp/` with an Xcode project referencing KMP outputs. Use Xcode to run the app or `./gradlew :client:linkDebugFrameworkIosArm64` etc. as needed. No custom scripts in this repo beyond standard Gradle KMP tasks.

### Running
- Server (Ktor/Netty): `./gradlew :server:run`
  - Main class is `kmpworkshop.server.ServerKt` (see `server/build.gradle.kts`).
  - The server persists/reads backup events from `serverBackup.json` and `serverEventBackup/` directory in the project root (see `Server.kt`). Keep these in version control for workshop state debugging; they are safe to reset between sessions.
- Admin client and other JVM apps can generally be run via `:adminClient:run` or IDE run configurations if defined.

### Configuration/Secrets
- The file `common/src/commonMain/kotlin/kmpworkshop/common/Secrets.kt` contains workshop API keys or placeholders. Do not commit real secrets. For local dev, prefer environment variables or local `local.properties` (already present) and read them at runtime if you extend secret handling.
- Network/RPC: The server uses kotlinx.rpc (krpc) with JSON serialization; keep versions aligned across modules.

## 2. Architecture Overview

This workshop models a multi-platform app plus a server, optimized to teach coroutines/flows and multiplatform structuring.

- common
  - Defines shared data models, serialization, and the puzzle endpoints' protocol: `CoroutinePuzzleEndPoint`, `CoroutinePuzzleEndpointCall/Answer`, `WorkshopStage`, etc.
  - Also hosts shared puzzle logic and client scaffolding surfaces used by tests.

- server
  - Entrypoint: `kmpworkshop.server.ServerKt` (`main()` -> `hostServer()`).
  - Exposes a `WorkshopApiService` constructed in `workshopService(serverState, onEvent)`.
  - Provides two classes of endpoints:
    - Standard “puzzle” endpoints using JSON in/out paired sequences (`Puzzle<T,R>`), with dynamic dispatch based on `WorkshopStage`.
    - Coroutine puzzle endpoints (`doCoroutinePuzzleSolveAttempt`) where the client streams structured calls and the server streams answers. These codify expected coroutine behavior (e.g., collect vs collectLatest semantics, timing constraints, parallelism correctness) and are thoroughly asserted in tests.
  - Maintains server state via a cold flow that replays from backups and persists subsequent events (uses `Channel`, Flow operators, coroutine scopes).

- client
  - Multiplatform module with shared logic for solving the puzzles (`ClientScaffolding`, `CoroutinePuzzleScaffolding`, `NumSumFun`) and target-specific entry points (Android/ios/jvm/native). Client exercises coroutine patterns as required by server tests.

- adminClient
  - Tools to inspect/control workshop state; depends on `serverAndAdminCommon` shared contracts.

- serverAndAdminCommon
  - Shared DTOs and APIs between server and admin client.

- testEnvironment / registration
  - Auxiliary modules to simulate endpoints and registration flows used throughout the workshop.

Data/Control Flow highlights:
- The server orchestrates puzzles based on the current `WorkshopStage`, serializes puzzle I/O via kotlinx.serialization, and validates solutions.
- Coroutine puzzles evaluate client behavior by inspecting call sequences and timing (e.g., enforcing `collectLatest` semantics by expecting cancellation of prior work upon rapid new emissions).

## 3. Testing

The project uses JUnit 5 for JVM modules and Kotlin Test integration via `kotlin("test")`. Each JVM module enables JUnit Platform (e.g., `server/build.gradle.kts` sets `useJUnitPlatform()`).

### Running existing tests
- All tests in the server module: `./gradlew :server:test`
- All tests in the repo: `./gradlew test`

Verification: On 2025-10-15 we ran `server/src/test/kotlin/kmpworkshop/server/CoroutinePuzzlesTest.kt` and all 12 tests passed under JUnit Platform. Additionally, we created a sample test `server/src/test/kotlin/kmpworkshop/server/MyNewTest.kt` (simple 2+2=4 assertion), executed it successfully, and then removed it to keep the repo clean.

### Adding a new test (example)
To add a JVM test in the server module:
1. Create a new Kotlin test file under `server/src/test/kotlin/<your/package>/MyNewTest.kt`.
2. Use JUnit 5 annotations and Kotlin test assertions:
   - `import org.junit.jupiter.api.Test`
   - `import kotlin.test.assertEquals`
3. Example body:
   - `@Test fun simpleSanity() { assertEquals(4, 2+2) }`
4. Run it with `./gradlew :server:test` or from the IDE.

Notes for coroutine/flow tests:
- Prefer `runBlocking` in tests as shown in `CoroutinePuzzlesTest.kt` when interacting with suspend functions/flows.
- For timing-sensitive tests (like the timed sum puzzle), keep tolerances aligned with server expectations; the server side will enforce performance windows and cancellation semantics.

### Multiplatform tests
- The `common` and `client` modules are KMP; place common tests under `common/src/test/kotlin` or `client/src/test/kotlin` where applicable. JVM-specific tests go under the corresponding `jvmTest` source set (if defined). Ensure the module’s Gradle file declares `kotlin("test")` in the right source set dependencies.

## 4. Additional Development Information

- Code style: Kotlin official style. Keep imports explicit and use idiomatic coroutines/flows. Enable `-Xcontext-parameters` is already configured in `server` compiler options.
- Serialization: Centralized on `kotlinx.serialization` (JSON). When adding new DTOs that cross the wire, annotate with `@Serializable` and maintain version compatibility with existing clients.
- Concurrency/coroutines:
  - Server code relies on structured concurrency and flow operators (`map`, `flatMapLatest`, `collectLatest`, cancellation). When changing server puzzles, consider cancellation behavior carefully: cancellation must be detected in the “collectLatest” puzzle, and time bounds must be enforced in the “timed sum” puzzle.
- Logging: Server uses `logback-classic`. When debugging test failures, increase logging via `logback.xml` (not currently present) or inline logs.
- Persistent state: Server writes backups to `serverBackup.json` and `serverEventBackup/`. Clear them to reset state during development; do not rely on them as authoritative fixtures.
- Admin/Server shared RPC contracts live in `serverAndAdminCommon`. Keep versions consistent with kotlinx.rpc plugin.
- Secrets and configuration: Avoid hard-coding real secrets in `Secrets.kt`. For local experimentation, you can stub values; for production-like setups, load from environment or properties files.

## 5. Known Pitfalls

- Mismatch in Kotlin versions across modules: root plugin versions differ for KMP (2.2.0) vs JVM (2.0.10). This is intentional in this workshop; do not blindly unify without checking plugin compatibility.
- Timing-sensitive tests can be flaky on heavily loaded CI; run locally or adjust timeouts only if you also adapt server-side checks.
- iOS toolchain is required for building actual iOS binaries. If you don’t need to run iOS locally, skip iOS targets during development.

## 6. Quick Reference Commands
- Build all: `./gradlew build`
- Run server: `./gradlew :server:run`
- Test all: `./gradlew test`
- Test server only: `./gradlew :server:test`
- Clean: `./gradlew clean`

