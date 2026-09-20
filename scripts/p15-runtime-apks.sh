#!/bin/bash
# P15 runtime smoke test — build the three APKs the scenarios need.
#
# The app's debug APK is arm64-only (`abiFilters += "arm64-v8a"`), and this host's emulator is x86_64, so
# each build adds x86_64 **in a throwaway worktree**: the branch's build configuration is not touched, and
# `abiFilters` changes nothing about the Kotlin/Dex code under test — only which native libs are packaged.
#
#   fixed    the branch HEAD with the startup fix
#   buggy    the P15 commit as pushed, whose nutrition tick init sits above the state it reads
#   p14      the pristine pre-P15 baseline, schema version 11, used to create the database that P15 upgrades
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export GRADLE_USER_HOME="$(pwd)/.gradle-home"

OUT=/tmp/p15-runtime
rm -rf "$OUT" && mkdir -p "$OUT"

build_one() {
    local label="$1" ref="$2"
    local dir="/tmp/p15-wt-$label"
    echo "=================== $label ($ref) ==================="
    rm -rf "$dir"
    git worktree remove "$dir" --force 2>/dev/null
    git worktree add --detach "$dir" "$ref" > "$OUT/$label.worktree.log" 2>&1 || {
        echo "worktree add failed"; tail -3 "$OUT/$label.worktree.log"; return 1
    }
    cp local.properties "$dir/local.properties" 2>/dev/null

    # x86_64 only, so the emulator can load the native lib; the app code is untouched.
    python3 - "$dir/app/build.gradle.kts" <<'PATCH'
import io, sys
path = sys.argv[1]
t = io.open(path, encoding="utf-8").read()
old = 'abiFilters += "arm64-v8a"'
if old not in t:
    sys.exit("abiFilters line not found in " + path)
t = t.replace(old, 'abiFilters += listOf("arm64-v8a", "x86_64")')
io.open(path, "w", encoding="utf-8").write(t)
PATCH

    ( cd "$dir" && rm -rf app/build/kspCaches app/build/generated/ksp \
        && ./gradlew --offline :app:assembleDebug > "$OUT/$label.build.log" 2>&1 )
    local status=$?
    local apk="$dir/app/build/outputs/apk/debug/app-debug.apk"
    if [ $status -ne 0 ] || [ ! -f "$apk" ]; then
        echo "BUILD FAILED ($status)"; tail -5 "$OUT/$label.build.log"; return 1
    fi
    cp "$apk" "$OUT/$label.apk"
    echo "built: $(md5sum "$OUT/$label.apk" | cut -d' ' -f1)  $(du -h "$OUT/$label.apk" | cut -f1)"
    echo "abis: $(unzip -l "$OUT/$label.apk" | grep -oE 'lib/[a-z0-9_-]+/' | sort -u | tr '\n' ' ')"
}

build_one fixed "$(cd "$(dirname "$0")/.." && git rev-parse HEAD)"
build_one buggy d74ee3a
build_one p14 6f56493

echo "=================== done ==================="
ls -la "$OUT"/*.apk
