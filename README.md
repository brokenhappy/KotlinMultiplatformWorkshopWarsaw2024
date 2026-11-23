
## Build requirements

The project uses one Java installation: **JetBrains Runtime 25**. It runs Gradle,
the Kotlin compiler, tests, Compose applications, and Compose Hot Reload. Kotlin
and Java compilation are restricted to the Java 23 bytecode and API level, so
the resulting artifacts remain Java 23 compatible.

Run the setup script once. It downloads the correct JBR 25 package for the
current operating system and architecture into the project-local `.jdk`
directory, then verifies the build:

```shell
./scripts/setup.sh
```

On Windows, run `.\scripts\setup.ps1`.

The Gradle wrappers automatically use that project-local runtime, regardless
of `JAVA_HOME`. The checked-in Gradle daemon criteria and Compose Hot Reload
also pin JBR 25. No system-wide Java installation, Temurin, Corretto, or second
local JDK is required.

## Local QA

Run the workshop smoke checks after setup.

```shell
./gradlew :serverAndClientTest:test --tests '*WorkshopLifecycleQaTest' --tests '*PuzzleUiQaTest'
```

The lifecycle check starts an empty workshop on a loopback port and exercises
the health endpoint, participant registration, host stage controls, and
submission tracking over real RPC. It uses a test-only password and in-memory
state. The Compose checks exercise the participant screen, including stage
changes, puzzle results, and the call timeline.

Run the full JVM suite with `./gradlew test --continue`. Gradle writes HTML
reports to each tested module's `build/reports/tests/test/index.html` and JUnit
XML to `build/test-results/test/` in the same module.

Here are the attributions for the sound snippets I used:

success.wav by sophieciruela -- https://freesound.org/s/634450/ -- License: Creative Commons 0
Computer Boop by fordps3 -- https://freesound.org/s/186669/ -- License: Creative Commons 0
Computer Says No by nlux -- https://freesound.org/s/623091/ -- License: Attribution 4.0
