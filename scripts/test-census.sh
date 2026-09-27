#!/usr/bin/env bash
# Fresh full-JVM-suite census, measured from the XML results and gated on the gradle exit code.
#
# Gating matters: the XML directory survives a failed build, so a parse-and-print step that runs
# whether or not gradle succeeded would report the *previous* run's numbers as this run's. The
# census is printed only on exit 0, and the exit code is printed beside it.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"

LOG=app/build/test-census-gradle.log
rm -rf app/build/kspCaches app/build/generated/ksp
./gradlew --offline :app:cleanTest :app:testDebugUnitTest --rerun-tasks --console=plain > "$LOG" 2>&1
CODE=$?
echo "gradle exit code: $CODE"
[ "$CODE" -eq 0 ] || { tail -40 "$LOG"; exit "$CODE"; }

python3 - app/build/test-results/testDebugUnitTest <<'CENSUS_BODY'
import sys, glob, os, re, datetime
directory = sys.argv[1]
classes = tests = failures = errors = skipped = 0
stamps = []
non_green = []
for path in sorted(glob.glob(os.path.join(directory, "*.xml"))):
    text = open(path, encoding="utf-8").read()
    head = re.search(r"<testsuite\b[^>]*>", text)
    if not head:
        continue
    attrs = dict(re.findall(r'(\w+)="([^"]*)"', head.group(0)))
    classes += 1
    tests += int(attrs.get("tests", 0))
    failures += int(attrs.get("failures", 0))
    errors += int(attrs.get("errors", 0))
    skipped += int(attrs.get("skipped", 0))
    if attrs.get("timestamp"):
        stamps.append(attrs["timestamp"])
    if int(attrs.get("failures", 0)) or int(attrs.get("errors", 0)):
        non_green.append(os.path.basename(path))
xmls = glob.glob(os.path.join(directory, "*.xml"))
newest = max((os.path.getmtime(p) for p in xmls), default=0)
print("classes: %d" % classes)
print("tests:   %d" % tests)
print("failures:%d" % failures)
print("errors:  %d" % errors)
print("skipped: %d" % skipped)
if stamps:
    print("xml timestamp range: %s .. %s" % (min(stamps), max(stamps)))
print("newest xml mtime (utc): %s" % datetime.datetime.utcfromtimestamp(newest).isoformat())
print("wall clock (utc):       %s" % datetime.datetime.utcnow().isoformat())
print("non-green suites: %s" % (non_green or "none"))
CENSUS_BODY
