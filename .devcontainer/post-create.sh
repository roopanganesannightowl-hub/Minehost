#!/usr/bin/env bash
# Post-create health check for the MineHost devcontainer.
# Prints every toolchain version and fails loudly only on hard errors so the
# Codespace always opens in a usable state.
set -euo pipefail

echo "==> Java"
java -version 2>&1 | head -1
echo "JAVA_HOME=${JAVA_HOME}"

echo "==> Node (Freebuff CLI requires Node >= 16)"
node --version
npm --version

echo "==> Android SDK"
echo "ANDROID_HOME=${ANDROID_HOME}"
sdkmanager --list_installed 2>/dev/null | grep -E "platform-tools|platforms;android|build-tools" || true
command -v adb >/dev/null && adb --version 2>/dev/null | head -1 || true

echo "==> Freebuff CLI"
if command -v freebuff >/dev/null 2>&1; then
  echo "freebuff -> $(command -v freebuff)"
  # The first run downloads the platform binary; never block container setup on it.
  freebuff --version 2>/dev/null \
    || echo "note: run 'freebuff' once in a terminal to finish its first-run download"
else
  echo "ERROR: freebuff is not on PATH" >&2
  exit 1
fi

echo "==> Gradle"
./gradlew --version 2>/dev/null | grep -E "^Gradle|^JVM:" || ./gradlew --version

echo ""
echo "==> MineHost devcontainer ready"
echo "    Build APKs : ./gradlew assembleStandardRelease assembleLocalRelease"
echo "    Unit tests : ./gradlew testStandardDebugUnitTest"
echo "    AI agent   : freebuff"
