
## Build requirements

The project uses JDK 23 for the Gradle daemon, compilation, tests, Compose applications, and the server runtime. The repository declares this in `gradle/gradle-daemon-jvm.properties`; Gradle will select or provision the JDK through its toolchain support.

Install the project toolchain once using [mise](https://mise.jdx.dev/):

```shell
./scripts/setup.sh
mise exec -- ./gradlew :client:test
```

On Windows, run `.\scripts\setup.ps1` and use `mise exec -- .\gradlew.bat :client:test`.
The Java version is pinned in `mise.toml`; no `JAVA_HOME` configuration is
needed. Gradle toolchains remain enabled as a fallback for Gradle-invoked JDKs.

Here are the attributions for the sound snippets I used:

success.wav by sophieciruela -- https://freesound.org/s/634450/ -- License: Creative Commons 0
Computer Boop by fordps3 -- https://freesound.org/s/186669/ -- License: Creative Commons 0
Computer Says No by nlux -- https://freesound.org/s/623091/ -- License: Attribution 4.0
