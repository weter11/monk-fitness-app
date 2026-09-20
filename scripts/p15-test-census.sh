#!/bin/bash
# P15 test census — runs a full fresh unit-test pass and parses the JUnit XML.
# Usage: scripts/p15-test-census.sh [extra gradle args...]
set -o pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export GRADLE_USER_HOME="$(pwd)/.gradle-home"

RESULTS=app/build/test-results/testDebugUnitTest
echo "### started $(date -u +%H:%M:%SZ)"

./gradlew --offline :app:cleanTest :app:testDebugUnitTest --rerun-tasks "$@" 2>&1 | tail -20
GRADLE_EXIT=${PIPESTATUS[0]}
echo "### gradle exit=$GRADLE_EXIT  finished $(date -u +%H:%M:%SZ)"

echo "### JUnit XML census ($RESULTS)"
python3 - "$RESULTS" <<'PY'
import sys, glob, os, datetime, xml.etree.ElementTree as ET
d = sys.argv[1]
files = sorted(glob.glob(os.path.join(d, "TEST-*.xml")))
if not files:
    print("NO XML FOUND in", d); sys.exit(1)
cls = tests = fail = err = skip = 0
bad = []
stamps = []
for f in files:
    r = ET.parse(f).getroot()
    cls += 1
    t = int(r.get("tests", 0)); tests += t
    fa = int(r.get("failures", 0)); fail += fa
    e = int(r.get("errors", 0)); err += e
    s = int(r.get("skipped", 0)); skip += s
    st = r.get("timestamp")
    if st: stamps.append(st)
    if fa or e:
        bad.append(f"{os.path.basename(f)}  tests={t} F={fa} E={e}")
print(f"classes={cls}  tests={tests}  failures={fail}  errors={err}  skipped={skip}")
if stamps:
    print(f"xml timestamp range: {min(stamps)}  ..  {max(stamps)}")
    print(f"now (utc):           {datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%S')}")
print(f"newest XML mtime:    {datetime.datetime.utcfromtimestamp(max(os.path.getmtime(f) for f in files)).strftime('%Y-%m-%d %H:%M:%S')} UTC")
if bad:
    print("### NON-GREEN SUITES:")
    for b in bad: print("   ", b)
PY
