#!/usr/bin/env bash
# Stage 6 RED mutations: every mutation must be caught and the source restored byte-identically.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$(pwd)/.gradle-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.daemon=false"

SOURCE=app/src/main/java/com/monkfitness/app/domain/program/target/TargetSchedulePolicy.kt
WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage6-red"
rm -rf "$WORK" && mkdir -p "$WORK"
cp "$SOURCE" "$WORK/TargetSchedulePolicy.kt.orig"
md5sum "$SOURCE" > "$WORK/before.md5"
PASS=0
FAIL=0
RESULTS=()

run_suites() {
  rm -rf app/build/kspCaches app/build/generated/ksp
  ./gradlew --offline :app:testDebugUnitTest \
    --tests 'com.monkfitness.app.domain.program.target.TargetSchedulePolicyTest' \
    --tests 'com.monkfitness.app.domain.program.target.TargetSchedulePolicyArchitectureTest' \
    --rerun-tasks --console=plain > "$WORK/last-run.log" 2>&1
}
restore() { cp "$WORK/TargetSchedulePolicy.kt.orig" "$SOURCE"; }
mutate() {
  local label="$1" old="$2" new="$3"
  restore
  python3 - "$SOURCE" "$old" "$new" <<'PY'
import sys
path, old, new = sys.argv[1:]
text = open(path, encoding='utf-8').read()
if old not in text:
    raise SystemExit('MUTATION ANCHOR NOT FOUND: ' + old)
updated = text.replace(old, new, 1)
if updated == text:
    raise SystemExit('MUTATION DID NOT APPLY: ' + old)
open(path, 'w', encoding='utf-8').write(updated)
PY
  if [ $? -ne 0 ]; then echo "ABORT: $label did not apply"; restore; exit 1; fi
  if run_suites; then echo "MISSED: $label"; RESULTS+=("MISSED $label"); FAIL=$((FAIL+1));
  else echo "caught: $label"; RESULTS+=("caught $label"); PASS=$((PASS+1)); fi
  restore
}

echo '== control =='
if run_suites; then echo 'control GREEN'; else echo 'control RED'; tail -30 "$WORK/last-run.log"; exit 1; fi

mutate 'asOf boundary' '!it.occurrence.plannedFor.isBefore(asOf) && it.occurrence.occurrenceKey !in plannedKeys' 'it.occurrence.occurrenceKey !in plannedKeys'
mutate 'asOf ignored' '!it.occurrence.plannedFor.isBefore(asOf) && it.occurrence.occurrenceKey in plannedKeys' 'it.occurrence.occurrenceKey in plannedKeys'
mutate 'pause predicate' 'pauses.any { it.covers(date) }' 'true'
mutate 'pause creation filter' '!isPaused(it.plannedFor, pauses)' 'true'
mutate 'past creation filter' '!it.plannedFor.isBefore(asOf) && !isPaused(it.plannedFor, pauses)' '!isPaused(it.plannedFor, pauses)'
mutate 'future revision supersession' '!it.occurrence.plannedFor.isBefore(asOf) && it.occurrence.occurrenceKey !in plannedKeys' 'it.occurrence.occurrenceKey !in plannedKeys'
mutate 'historical preservation' 'targetPlan.reconciliation.preserved.canonicalExistingOrder()' 'emptyList()'
mutate 'identity matching' 'val plannedKeys = targetPlan.planned.mapTo(HashSet()) { it.occurrenceKey }' 'val plannedKeys = targetPlan.planned.map { it.plannedFor.toString() }.toSet()'
mutate 'output ordering' 'missed = pastUnpaused.canonicalExistingOrder()' 'missed = pastUnpaused.reversed()'
mutate 'scheduler reference' 'object TargetSchedulePolicy {' 'object TargetSchedulePolicy { private val scheduler = ProgramScheduler.hashCode()'
mutate 'clock reference' 'object TargetSchedulePolicy {' 'object TargetSchedulePolicy { private val clock = Clock.systemUTC()'
mutate 'mutable singleton state' 'object TargetSchedulePolicy {' 'object TargetSchedulePolicy { private var cache: List<ExistingOccurrence> = emptyList()'

restore
if md5sum -c "$WORK/before.md5"; then echo 'source restored byte-identically'; else FAIL=$((FAIL+1)); RESULTS+=('MISSED byte-identical restoration'); fi
printf '%s\n' "${RESULTS[@]}"
echo "caught: $PASS   missed: $FAIL"
[ "$FAIL" -eq 0 ]
