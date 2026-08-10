#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
INSTALL_DIR="$ROOT_DIR/.jdk"
cd "$ROOT_DIR"

is_expected_jbr() {
    [ -x "$INSTALL_DIR/bin/java" ] &&
        "$INSTALL_DIR/bin/java" -XshowSettings:properties -version 2>&1 |
        grep -Eq 'java\.vendor = JetBrains' &&
        "$INSTALL_DIR/bin/java" -XshowSettings:properties -version 2>&1 |
        grep -Eq 'java\.specification\.version = 25'
}

if ! is_expected_jbr; then
    case "$(uname -s)-$(uname -m)" in
        Darwin-arm64)  PACKAGE_ID=690f22c7d5fabdba81db34b2bcbdfd40 ;;
        Darwin-x86_64) PACKAGE_ID=19d7c2d18fdc600f184666745a0567c4 ;;
        Linux-aarch64) PACKAGE_ID=5c55020ad1e1758e65cf84d78a6991d9 ;;
        Linux-x86_64)  PACKAGE_ID=057be4d72513d094c747045acda26562 ;;
        *) echo "Unsupported platform: $(uname -s) $(uname -m)" >&2; exit 1 ;;
    esac

    command -v curl >/dev/null 2>&1 || { echo "curl is required to install JBR 25." >&2; exit 1; }
    TEMP_DIR=$(mktemp -d)
    trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM
    echo "Downloading JetBrains Runtime 25..."
    curl --fail --location --progress-bar \
        "https://api.foojay.io/disco/v3.0/ids/$PACKAGE_ID/redirect" \
        --output "$TEMP_DIR/jbr.tar.gz"
    tar -xzf "$TEMP_DIR/jbr.tar.gz" -C "$TEMP_DIR"
    JAVA_BINARY=$(find "$TEMP_DIR" -type f -path '*/bin/java' -perm -u+x | head -n 1)
    [ -n "$JAVA_BINARY" ] || { echo "The downloaded archive did not contain a JDK." >&2; exit 1; }
    JAVA_HOME_DIR=$(dirname "$(dirname "$JAVA_BINARY")")
    rm -rf "$INSTALL_DIR"
    mkdir -p "$INSTALL_DIR"
    cp -R "$JAVA_HOME_DIR/." "$INSTALL_DIR"
fi

echo "Verifying Java and Gradle..."
"$INSTALL_DIR/bin/java" -version
./gradlew --version
./gradlew :client:classes :adminClient:classes

echo "Setup complete. The Gradle wrapper automatically uses $INSTALL_DIR."
echo "Run Gradle normally, for example:"
echo "  ./gradlew :client:test"
