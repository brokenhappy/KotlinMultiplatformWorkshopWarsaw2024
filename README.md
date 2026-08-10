
## Build requirements

The project uses one Java installation: **JetBrains Runtime 25**. It runs Gradle,
the Kotlin compiler, tests, Compose applications, and Compose Hot Reload. Kotlin
and Java compilation are restricted to the Java 23 bytecode and API level, so
the resulting artifacts remain Java 23 compatible.

IntelliJ IDEA bundles JBR. Select its JBR 25 as the project and Gradle JVM, or
install JBR 25 separately and point `JAVA_HOME` to it. Then verify the complete
setup once:

```shell
./scripts/setup.sh
```

On Windows, run `.\scripts\setup.ps1`.

The checked-in Gradle daemon criteria pin JetBrains Runtime 25 and include
provisioning URLs for macOS, Linux, and Windows. Compose Hot Reload uses the
same JBR generation. No Temurin, Corretto, or second local JDK is required.

Here are the attributions for the sound snippets I used:

success.wav by sophieciruela -- https://freesound.org/s/634450/ -- License: Creative Commons 0
Computer Boop by fordps3 -- https://freesound.org/s/186669/ -- License: Creative Commons 0
Computer Says No by nlux -- https://freesound.org/s/623091/ -- License: Attribution 4.0
