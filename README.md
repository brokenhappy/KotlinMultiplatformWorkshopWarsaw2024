
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
the public health endpoint, invitation-code rejection, participant registration
with a Unicode code, host stage controls, and submission tracking over real RPC.
It uses fixture credentials and in-memory state. The Compose checks exercise the
participant screen, including stage changes, puzzle results, and the call timeline.

Run the full JVM suite with `./gradlew test --continue`. Gradle writes HTML
reports to each tested module's `build/reports/tests/test/index.html` and JUnit
XML to `build/test-results/test/` in the same module.

## Local credentials

Export `ADMIN_PASSWORD` for both the local server and the admin client. The
server also accepts the deployment variable `admin_access_password`, which
takes precedence when set.

Set `WORKSHOP_INVITATION_CODE` to the same non-blank, single-line code in the
server and admin client environments. When connecting, clients send the UTF-8
code as unpadded Base64url in the `X-Workshop-Invitation-Code` header.
`GET /healthz` is public.

Participants enter their name and the host's invitation code in
`registerMyselfWithTheNameIWillUseForTheRestOfTheSessionsAndMyInvitationCode(name, invitationCode)`.
Registration saves the participant API key on the first line of
`.workshop-api-key` at the checkout root and the
invitation code on the second line. The desktop client and registration
verification also accept `WORKSHOP_API_KEY` and `WORKSHOP_INVITATION_CODE`
from their environment. For an existing registration, keep the API key and
add the invitation code on the second line or set `WORKSHOP_INVITATION_CODE`.

Here are the attributions for the sound snippets I used:

success.wav by sophieciruela -- https://freesound.org/s/634450/ -- License: Creative Commons 0
Computer Boop by fordps3 -- https://freesound.org/s/186669/ -- License: Creative Commons 0
Computer Says No by nlux -- https://freesound.org/s/623091/ -- License: Attribution 4.0
