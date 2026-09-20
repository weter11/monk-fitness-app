#!/bin/bash
# Localization-stage runtime smoke test — build the two APKs the scenarios need.
#
# The app's debug APK is arm64-only (`abiFilters += "arm64-v8a"`), and this host's emulator is x86_64, so
# each build adds x86_64 in a throwaway tree: the stage's own build configuration is not touched, and
# `abiFilters` changes nothing about the Kotlin/Dex code under test — only which native libs are packaged.
#
#   new      this stage's tree, with the application-locale mechanism and the seven languages
#   legacy   the commit the stage started from: the three-button selector, `language ?: "ru"`, and the
#            DataStore key the migration exists for. Used to create an *existing* installation.
set -uo pipefail
REPO=/home/wer/devis/monk-fitness-app
OUT=/tmp/l10n-runtime
export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export GRADLE_USER_HOME="$REPO/.gradle-home"

rm -rf "$OUT" && mkdir -p "$OUT"
cd "$REPO" || exit 1

patch_abi() {
    python3 - "$1" <<'PATCH'
import io, sys
path = sys.argv[1]
t = io.open(path, encoding="utf-8").read()
old = 'abiFilters += "arm64-v8a"'
if old not in t:
    sys.exit("abiFilters line not found in " + path)
io.open(path, "w", encoding="utf-8").write(t.replace(old, 'abiFilters += listOf("arm64-v8a", "x86_64")'))
PATCH
}

build_new() {
    local dir="$OUT/new"
    echo "=================== new ($(git -C "$REPO" rev-parse HEAD) + working tree) ==================="
    mkdir -p "$dir"
    tar -c --exclude=.git --exclude=.gradle-home --exclude=.gradle --exclude=build --exclude='app/build' \
        -C "$REPO" . | tar -x -C "$dir"
    patch_abi "$dir/app/build.gradle.kts" || return 1
    ( cd "$dir" && ./gradlew --offline :app:assembleDebug > "$OUT/new.build.log" 2>&1 )
    local status=$?
    local apk="$dir/app/build/outputs/apk/debug/app-debug.apk"
    if [ $status -ne 0 ] || [ ! -f "$apk" ]; then
        echo "BUILD FAILED ($status)"; tail -20 "$OUT/new.build.log"; return 1
    fi
    cp "$apk" "$OUT/new.apk"
    echo "built: $(md5sum "$OUT/new.apk" | cut -d' ' -f1)  $(du -h "$OUT/new.apk" | cut -f1)"
    unzip -l "$OUT/new.apk" | grep -oE 'lib/[a-z0-9_-]+/' | sort -u | tr '\n' ' '; echo
}

build_legacy() {
    local dir="$OUT/legacy"
    echo "=================== legacy ($(git -C "$REPO" rev-parse HEAD)) ==================="
    git -C "$REPO" worktree remove "$dir" --force 2>/dev/null
    git -C "$REPO" worktree add --detach "$dir" HEAD > "$OUT/legacy.worktree.log" 2>&1 || {
        echo "worktree add failed"; tail -3 "$OUT/legacy.worktree.log"; return 1
    }
    cp "$REPO/local.properties" "$dir/local.properties" 2>/dev/null
    patch_abi "$dir/app/build.gradle.kts" || return 1
    ( cd "$dir" && ./gradlew --offline :app:assembleDebug > "$OUT/legacy.build.log" 2>&1 )
    local status=$?
    local apk="$dir/app/build/outputs/apk/debug/app-debug.apk"
    if [ $status -ne 0 ] || [ ! -f "$apk" ]; then
        echo "BUILD FAILED ($status)"; tail -20 "$OUT/legacy.build.log"; return 1
    fi
    cp "$apk" "$OUT/legacy.apk"
    echo "built: $(md5sum "$OUT/legacy.apk" | cut -d' ' -f1)  $(du -h "$OUT/legacy.apk" | cut -f1)"
    unzip -l "$OUT/legacy.apk" | grep -oE 'lib/[a-z0-9_-]+/' | sort -u | tr '\n' ' '; echo
}

build_new
build_legacy
echo "=================== done ==================="
ls -la "$OUT"/*.apk
