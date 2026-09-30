# syntax=docker/dockerfile:1
# MineHost development container for GitHub Codespaces / VS Code Dev Containers.
# Toolchain: JDK 17 (AGP 8.7 / Kotlin 2.0), Android SDK 35 + build-tools 35.0.0,
# Node.js 22 with the Freebuff CLI preinstalled, and warmed Gradle caches so the
# first in-container build is fast and fully offline-ready.
FROM mcr.microsoft.com/devcontainers/base:ubuntu-24.04

# The base image defaults to USER vscode; toolchain installation needs root.
USER root

ARG DEBIAN_FRONTEND=noninteractive
ARG NODE_MAJOR=22
# Google command-line tools release (sdkmanager / avdmanager).
ARG ANDROID_CMDLINE_TOOLS=11076708

# ---------------------------------------------------------------- 1. base packages
RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-17-jdk-headless \
        git gh curl wget unzip zip xz-utils jq ripgrep less tree \
        ca-certificates gnupg \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# ---------------------------------------------------------------- 2. Node.js (Freebuff CLI needs Node >= 16)
RUN mkdir -p /etc/apt/keyrings \
    && curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key \
        | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_${NODE_MAJOR}.x nodistro main" \
        > /etc/apt/sources.list.d/nodesource.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends nodejs \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# ---------------------------------------------------------------- 3. stable arch-independent JAVA_HOME
RUN ln -sfn "$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")" /opt/java-17
ENV JAVA_HOME=/opt/java-17
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# ---------------------------------------------------------------- 4. Android SDK
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
    && curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS}_latest.zip" \
        -o /tmp/cmdline-tools.zip \
    && unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
    && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm -f /tmp/cmdline-tools.zip
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"
RUN yes | sdkmanager --licenses > /dev/null 2>&1 \
    && sdkmanager --install \
        "platform-tools" \
        "platforms;android-35" \
        "build-tools;35.0.0" \
    && sdkmanager --list_installed

# ---------------------------------------------------------------- 5. Freebuff CLI (preinstalled)
RUN npm install -g freebuff \
    && npm cache clean --force \
    && command -v freebuff
# Pre-fetch the platform binary as `vscode` (it caches to ~/.config/manicode,
# so a root-run download would land in the wrong home). Non-fatal: if the CDN
# hiccups during the image build, the CLI simply re-downloads on first use.
USER vscode
# HOME is pinned explicitly: BuildKit does not always export it for non-root
# RUN steps, and the launcher caches to $HOME/.config/manicode.
RUN HOME=/home/vscode timeout 600 freebuff --version \
    || echo "note: freebuff pre-fetch skipped (will download on first use)"
USER root

# ---------------------------------------------------------------- 6. warm Gradle caches + full build validation
# Runs as `vscode` so ~/.gradle lands in the account Codespaces actually uses.
# Assembling every flavor and running the unit tests here guarantees that a
# fresh Codespace can build the project with zero missing pieces.
# WORKDIR creates directories owned by root; grant vscode write access before
# the warm-up build writes app/build and .gradle inside it.
WORKDIR /tmp/mh-warmup
RUN chown vscode:vscode /tmp/mh-warmup
USER vscode
COPY --chown=vscode:vscode gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY --chown=vscode:vscode gradle ./gradle
COPY --chown=vscode:vscode app/build.gradle.kts app/proguard-rules.pro ./app/
COPY --chown=vscode:vscode app/src ./app/src
# retry() runs a command twice, failing the image build if both attempts fail.
# (A plain `a || a' && b || b'` chain parses left-associative and could let a
# successful assemble mask failed tests.)
RUN retry() { for i in 1 2; do "$@" && return 0; echo "attempt $i failed, retrying: $*"; done; return 1; }; \
    retry ./gradlew --no-daemon --stacktrace testStandardDebugUnitTest \
    && retry ./gradlew --no-daemon --stacktrace assembleStandardRelease assembleLocalRelease \
    && rm -rf /tmp/mh-warmup

# Default location Codespaces mounts the repository into.
WORKDIR /workspaces/MineHost
USER vscode
