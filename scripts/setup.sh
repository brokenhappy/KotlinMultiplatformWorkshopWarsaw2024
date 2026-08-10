#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"

if ! command -v java >/dev/null 2>&1; then
    echo "Java was not found. Select or install JetBrains Runtime 25 and set JAVA_HOME." >&2
    exit 1
fi

JAVA_DETAILS=$(java -XshowSettings:properties -version 2>&1)
if ! printf '%s\n' "$JAVA_DETAILS" | grep -Eq 'java\.vendor = JetBrains'; then
    echo "This project requires JetBrains Runtime 25; the active Java vendor is not JetBrains." >&2
    exit 1
fi
if ! printf '%s\n' "$JAVA_DETAILS" | grep -Eq 'java\.specification\.version = 25'; then
    echo "This project requires JetBrains Runtime 25; the active Java version is not 25." >&2
    exit 1
fi

echo "Verifying Java and Gradle..."
java -version
./gradlew --version
./gradlew :client:test

echo "Setup complete. Run Gradle normally, for example:"
echo "  ./gradlew :client:test"
