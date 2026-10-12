#!/usr/bin/env bash
# Builds the mod from inside this folder. No Fabric template files needed.
# Usage: ./build.sh            (builds the jar)
#        ./build.sh runClient  (starts a test Minecraft)
set -euo pipefail
cd "$(dirname "$0")"

GRADLE_VERSION="9.6.0"
TASKS=("$@")
[ ${#TASKS[@]} -eq 0 ] && TASKS=(build)

# --- 1. Java: a full JDK 25 is required (a JRE alone has no compiler) ---
JAVAC=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
elif [ -z "${JAVA_HOME:-}" ] && command -v javac >/dev/null 2>&1; then
  JAVAC="$(command -v javac)"
fi
if [ -z "$JAVAC" ]; then
  echo "ERROR: no Java compiler (javac) found. You have a JRE, but building needs the full JDK 25." >&2
  echo "  Debian/Ubuntu: sudo apt install openjdk-25-jdk" >&2
  echo "  Fedora:        sudo dnf install java-25-openjdk-devel" >&2
  echo "  Arch:          sudo pacman -S jdk25-openjdk" >&2
  echo "  Other:         install Temurin JDK 25 from https://adoptium.net" >&2
  [ -n "${JAVA_HOME:-}" ] && echo "  (JAVA_HOME is set to $JAVA_HOME and has no bin/javac: fix or unset it)" >&2
  exit 1
fi
JV="$("$JAVAC" -version 2>&1 | awk '{print $2}' | cut -d. -f1)"
if [ "${JV:-0}" -lt 25 ]; then
  echo "ERROR: javac is version $JV, but JDK 25 or newer is required." >&2
  exit 1
fi

# --- 2. Gradle: use the wrapper if present, otherwise download Gradle into this folder ---
if [ -f gradle/wrapper/gradle-wrapper.jar ] && [ -x ./gradlew ]; then
  GRADLE_CMD=(./gradlew)
else
  DIST=".gradle-dist/gradle-$GRADLE_VERSION"
  if [ ! -x "$DIST/bin/gradle" ]; then
    echo "Downloading Gradle $GRADLE_VERSION (one time, about 130 MB)..."
    mkdir -p .gradle-dist
    URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    if command -v curl >/dev/null 2>&1; then curl -fL "$URL" -o .gradle-dist/gradle.zip
    elif command -v wget >/dev/null 2>&1; then wget -O .gradle-dist/gradle.zip "$URL"
    else echo "ERROR: need curl or wget to download Gradle." >&2; exit 1; fi
    if command -v unzip >/dev/null 2>&1; then unzip -q .gradle-dist/gradle.zip -d .gradle-dist
    else python3 -c "import zipfile;zipfile.ZipFile('.gradle-dist/gradle.zip').extractall('.gradle-dist')"; chmod +x "$DIST/bin/gradle"; fi
    rm -f .gradle-dist/gradle.zip
  fi
  GRADLE_CMD=("$DIST/bin/gradle")
fi

# --- Fix broken file timestamps (some unzip tools leave invalid dates, which makes Gradle fail) ---
find . -path ./.gradle-dist -prune -o -exec touch {} + 2>/dev/null || true

# --- 3. Build ---
"${GRADLE_CMD[@]}" "${TASKS[@]}"

if [ -d build/libs ]; then
  echo
  echo "Done. Your jar is in build/libs/ (use the one WITHOUT '-sources'):"
  ls -1 build/libs
fi
