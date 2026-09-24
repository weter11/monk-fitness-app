#!/usr/bin/env bash
# Stage 2 RED mutations: every deliberate break must be caught by the focused pure suites.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
export ANDROID_HOME=/home/wer/devis/android-sdk

SOURCE=app/src/main/java/com/monkfitness/app/domain/program/target/TargetScheduleResolver.kt
WORK="${TMPDIR:-/home/wer/.hermes/cache/scratch}/program-stage2-red"
rm -rf "$WORK" && mkdir -p "$WORK"
cp "$SOURCE" "$WORK/TargetScheduleResolver.kt.orig"
md5sum "$SOURCE" > "$WORK/before.md5"
PASS=0
FAIL=0
RESULTS=()

run_suites() {
    rm -rf app/build/kspCaches app/build/generated/ksp
    ./gradlew --offline :app:testDebugUnitTest \
        --tests 'com.monkfitness.app.domain.program.target.TargetScheduleResolverTest' \
        --tests 'com.monkfitness.app.domain.program.target.TargetScheduleArchitectureTest' \
        --rerun-tasks --console=plain > "$WORK/last-run.log" 2>&1
}

restore() { cp "$WORK/TargetScheduleResolver.kt.orig" "$SOURCE"; }

mutate() {
    local label="$1" old="$2" new="$3"
    restore
    python3 - "$SOURCE" "$old" "$new" <<'PY'
import sys
path, old, new = sys.argv[1:]
text = open(path, encoding='utf-8').read()
if old not in text:
    raise SystemExit('MUTATION ANCHOR NOT FOUND: ' + old)
open(path, 'w', encoding='utf-8').write(text.replace(old, new, 1))
PY
    if [ $? -ne 0 ]; then
        echo "ABORT: $label did not apply"
        restore
        exit 1
    fi
    if run_suites; then
        echo "MISSED: $label"
        RESULTS+=("MISSED $label")
        FAIL=$((FAIL + 1))
    else
        echo "caught: $label"
        RESULTS+=("caught $label")
        PASS=$((PASS + 1))
    fi
    restore
}

echo '== control =='
if run_suites; then
    echo 'control GREEN'
else
    echo 'control RED'
    tail -30 "$WORK/last-run.log"
    exit 1
fi

mutate 'SessionsPerWeek derived from existing slots' \
    'val weekdays = weeklyWeekdays(schedule.cadence.sessionsPerWeek)' \
    'val weekdays = weeklyWeekdays((schedule.cadence.sessionsPerWeek + (source?.occurrences?.size ?: 0)).coerceIn(1, 7))'

mutate 'SessionsPerWeek randomized' \
    'private fun weeklyWeekdays(sessionsPerWeek: Int): Set<DayOfWeek> = when (sessionsPerWeek) {' \
    'private fun weeklyWeekdays(sessionsPerWeek: Int): Set<DayOfWeek> = DayOfWeek.entries.shuffled().take(sessionsPerWeek).toSet() + when (sessionsPerWeek) {'

mutate 'SessionsPerWeek converted to EveryNDays' \
    'val weekdays = weeklyWeekdays(schedule.cadence.sessionsPerWeek)' \
    'val weekdays = weeklyWeekdays((7 / schedule.cadence.sessionsPerWeek).coerceIn(1, 7))'

mutate 'wrong SessionsPerWeek spread' \
    '3 -> setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)' \
    '3 -> setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY)'

mutate 'window end made exclusive' \
    '!date.isBefore(from) && !date.isAfter(through)' \
    '!date.isBefore(from) && date.isBefore(through)'

mutate 'anchor ignored' \
    '.filterNot { it.isBefore(schedule.anchorDate) }' \
    '.filterNot { false }'

mutate 'FixedWeekdays order depends on Set iteration' \
    'it.dayOfWeek in schedule.cadence.weekdays' \
    'schedule.cadence.weekdays.any { weekday -> weekday == it.dayOfWeek } && schedule.cadence.weekdays.first() == DayOfWeek.MONDAY'

mutate 'DerivedExcluding treated as Daily' \
    'val excludedDates = source.occurrences.map { it.plannedDate }.toSet()' \
    'val excludedDates = emptySet<LocalDate>()'

mutate 'resolver reads a clock' \
    'ScheduleCadence.Daily -> window.dates()' \
    'ScheduleCadence.Daily -> window.dates().filterNot { it.isBefore(LocalDate.now()) }'

mutate 'resolver mutates FixedWeekdays input' \
    'is ScheduleCadence.FixedWeekdays -> window.dates()' \
    'is ScheduleCadence.FixedWeekdays -> (schedule.cadence.weekdays as MutableSet<DayOfWeek>).apply { add(DayOfWeek.SUNDAY) }; window.dates()'

restore
echo '== restoration =='
if md5sum -c "$WORK/before.md5"; then
    echo 'source restored byte-identically'
else
    FAIL=$((FAIL + 1))
    RESULTS+=('MISSED byte-identical restoration')
fi

printf '%s\n' "${RESULTS[@]}"
echo "caught: $PASS   missed: $FAIL"
[ "$FAIL" -eq 0 ]
