#!/bin/sh

set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"

if ! command -v mise >/dev/null 2>&1; then
    echo "mise is required to provision Java 23." >&2
    echo "Install it from https://mise.jdx.dev/getting-started.html, then run this script again." >&2
    exit 1
fi

echo "Installing the project Java toolchain..."
mise install

echo "Verifying Java and Gradle..."
mise exec -- java -version
mise exec -- ./gradlew --version
mise exec -- ./gradlew :client:test

echo "Setup complete. Run Gradle commands through mise, for example:"
echo "  mise exec -- ./gradlew :client:test"
