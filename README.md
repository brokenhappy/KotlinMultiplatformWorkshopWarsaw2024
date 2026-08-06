
## Build requirements

The local call-tree compiler plugin requires the Gradle daemon to run on JDK 23 or newer. The repository declares this in `gradle/gradle-daemon-jvm.properties`; Gradle will select or provision that daemon JDK. The published call-tree UI is compiled for JDK 25, so JDK 25 must also be available for the affected module toolchains.

```shell
./gradlew :client:test
```

Here are the attributions for the sound snippets I used:

success.wav by sophieciruela -- https://freesound.org/s/634450/ -- License: Creative Commons 0
Computer Boop by fordps3 -- https://freesound.org/s/186669/ -- License: Creative Commons 0
Computer Says No by nlux -- https://freesound.org/s/623091/ -- License: Attribution 4.0
