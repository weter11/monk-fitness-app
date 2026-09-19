#!/usr/bin/env bash
# §30 step 12 — the deliberate RED mutations of the Adaptive Integration.
#
# Each block applies one mutation to a *production* source, runs the focused §30-step-12 suites (the
# integration suite, the target-selection/judgement suite, the session runtime's own suites and the
# engine's), and records whether the suites caught it. A rule whose mutation does NOT fail a test is a
# rule the suites do not prove, so this file is the evidence that the future-target rule, its temporal
# clause, the program and revision boundaries, §18's filter, §13's stored reason, §11's window
# bookkeeping, §15's ownership rule, §16's supersession and consumption, and §27's unit of work are
# pinned by tests rather than by prose.
#
# Usage: bash scripts/program-adaptive-integration-red-mutations.sh
# Requires: the toolchain env (JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME) exported in the SAME shell.
# A mis-env'd run fakes BOTH a pass and a miss, so export first:
#   export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
#   export ANDROID_HOME=/home/wer/devis/android-sdk
#   export GRADLE_USER_HOME="$(pwd)/.gradle-home"
#
# The script restores every mutated file from a backup and verifies the restoration by md5 at the end,
# so a run leaves the reviewed bytes exactly as they were.

set -u
cd "$(dirname "$0")/.."

TMP="$(mktemp -d)"
PASS=0
FAIL=0
FAILED_MUTATIONS=()



INTEGRATION="app/src/main/java/com/monkfitness/app/domain/usecase/ProgramAdaptiveIntegration.kt"
RUNTIME="app/src/main/java/com/monkfitness/app/domain/usecase/SessionRuntime.kt"
TARGET="app/src/main/java/com/monkfitness/app/domain/adaptive/integration/AdaptiveTargetSlot.kt"
ELEMENT="app/src/main/java/com/monkfitness/app/domain/adaptive/engine/ProgramAdaptiveElement.kt"
MAPPER="app/src/main/java/com/monkfitness/app/data/mapper/AdaptiveMappers.kt"
CONTAINER="app/src/main/java/com/monkfitness/app/di/AppContainer.kt"

SOURCES=(
  "$INTEGRATION"
  "$RUNTIME"
  "$TARGET"
  "$ELEMENT"
  "$MAPPER"
  "$CONTAINER"
)

# ------------------------------------------------------------------------------------------------
# PRE-FLIGHT. Two runs of this script on one working tree corrupt both of them — one restores the
# bytes the other is mid-mutation on, and whichever takes its md5 baseline first then "proves" a
# restoration of the *mutated* bytes. That happened once, so the tree is checked before anything is
# touched: every mutation's anchor must be present (the reviewed bytes are on disk) and no mutation's
# replacement may already be there (a leftover from an interrupted run). The baseline md5 below is
# only taken once this passes, which is what makes `md5sum -c` at the end mean anything.
# ------------------------------------------------------------------------------------------------
python3 - "$INTEGRATION" "$RUNTIME" "$TARGET" "$ELEMENT" "$MAPPER" "$CONTAINER" <<'PY'
import sys

integration, runtime, target, element, mapper, container = sys.argv[1:7]

# (anchor that must be present, replacement that must be absent)
checks = [
    (target, "slot.slotId != completedSlotId &&", "slot.attempts.isEmpty() &&\n                slot.isStartable\n"),
    (target, "slot.plannedFor.isAfter(notBefore)", "slot.plannedFor.isBefore(notBefore)"),
    (target, "slot.isStartable &&", "true &&"),
    (target, "slot.attempts.isEmpty() &&", "true &&"),
    (runtime, "if (decision.programId != session.programId) {", "if (false) {\n            return refusal(SessionRefusal.AdaptiveTargetRefusal.ANOTHER_PROGRAM)"),
    (runtime, "if (decision.revisionId != session.revisionId) {", "if (false) {\n            return refusal(SessionRefusal.AdaptiveTargetRefusal.ANOTHER_REVISION)"),
    (runtime, "if (decision.slotId == session.slotId) {", "if (false) {\n            return refusal(SessionRefusal.AdaptiveTargetRefusal.THE_COMPLETED_SLOT_ITSELF)"),
    (runtime, "if (!target.isStartable) {", "if (false) {\n            return refusal(SessionRefusal.AdaptiveTargetRefusal.SLOT_IS_NOT_AHEAD_OF_THE_USER)"),
    (runtime, "if (!target.plannedFor.isAfter(decisionDay)) {", "        if (false) {"),
    (integration, "result.wasFilteredByTheGuard && state != null -> AdaptiveIntegrationOutcome.AdaptiveFiltered(", "false && state != null -> AdaptiveIntegrationOutcome.AdaptiveFiltered("),
    (integration, "else -> AdaptiveIntegrationOutcome.NothingToAdapt(reason = result.reason, familyState = state)", "else -> AdaptiveIntegrationOutcome.AdaptiveFiltered("),
    (integration, "private fun advanced(stored: Int?, qualified: Boolean): Int = if (qualified) (stored ?: 0) + 1 else 0", "private fun advanced(stored: Int?, qualified: Boolean): Int = stored ?: 0"),
    (integration, "level = if (completed >= prescribed) ExposureLevel.FULL else ExposureLevel.PARTIAL,", "level = ExposureLevel.FULL,"),
    (target, "        ?.let { exercise -> classification.familyOf(exercise.exerciseId) }", "        return presentation.exerciseId"),
    (integration, "        .maxByOrNull { it.createdAt }\n        ?.adjustmentId", "    ): AdjustmentId? = null"),
    (integration, "if (revision.mode == ProgramMode.MANUAL) return gap(AdaptiveInputGap.PROGRAM_MODE_IS_MANUAL)", "if (false) return gap(AdaptiveInputGap.PROGRAM_MODE_IS_MANUAL)"),
    (mapper, "    reason = reason?.name", "    reason = ProgramAdaptiveReason.INSUFFICIENT_EVIDENCE.name"),
    (runtime, "            familyState?.let { adaptiveRepository.saveFamilyState(it) }", "familyState-leg-dropped-placeholder"),
    (runtime, "        val standing = standingAdjustments(adaptiveRepository.adjustmentsOf(slotId))", "emptyList<com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment>()"),
    (element, "        get() = ownership == ProgramElementOwnership.AUTOMATIC", "        get() = true"),
    (container, "        adaptiveRepository = programAdaptiveRepository,", "        adaptiveRepository = adaptiveRepository,"),
]

problems = []
for path, anchor, replacement in checks:
    text = open(path, encoding="utf-8").read()
    if anchor not in text:
        problems.append(f"anchor missing (the file is not the reviewed byte): {path}: {anchor[:60]!r}")
    if replacement and replacement not in ("familyState-leg-dropped-placeholder",) and replacement in text:
        problems.append(f"a mutation's replacement is already present: {path}: {replacement[:60]!r}")

if problems:
    print("PRE-FLIGHT FAILED — refusing to run, because a baseline taken now would prove nothing:")
    for problem in problems:
        print("  * " + problem)
    sys.exit(1)
print("pre-flight ok: every mutation anchor is present and no mutation is already applied")
PY
preflight=$?
if [ "$preflight" -ne 0 ]; then
  echo
  echo "ABORTED: the working tree is not the reviewed byte. Restore it before running the mutations."
  exit 1
fi

# Only one runner may touch this tree at a time: two would restore each other's mutations.
LOCK="$TMP/../p12-red-mutations.lock"
if [ -e /tmp/p12-red-mutations.lock ]; then
  echo "ABORTED: another run of this script is in flight (/tmp/p12-red-mutations.lock exists)."
  exit 1
fi
echo "$$" > /tmp/p12-red-mutations.lock
trap 'rm -f /tmp/p12-red-mutations.lock' EXIT

# md5 of the reviewed bytes, so the restore is provable rather than assumed.
md5sum "${SOURCES[@]}" > "$TMP/before.md5"

# apply <label> <file> <old> <new>
apply() {
  local label="$1" file="$2" old="$3" new="$4"
  cp "$file" "$TMP/$(echo "$label" | tr ' ' '_').bak"
  python3 - "$file" "$old" "$new" <<'PY'
import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(path, encoding="utf-8").read()
# A mismatch is NOT a mutation that survived: it is a script defect, and scoring it as a "missed"
# rule would blame the tests for the harness's own indentation. Abort instead.
if old not in text:
    print(f"FATAL: mutation anchor not found in {path}: {old[:70]!r}")
    sys.exit(2)
written = text.replace(old, new, 1)
if written == text:
    print(f"FATAL: the mutation changed nothing in {path}: {old[:70]!r}")
    sys.exit(2)
open(path, "w", encoding="utf-8").write(written)
PY
  if [ $? -ne 0 ]; then
    echo "ABORTED: this mutation could not be applied — see the FATAL line above."
    exit 1
  fi
}

restore() {
  local label="$1" file="$2"
  cp "$TMP/$(echo "$label" | tr ' ' '_').bak" "$file"
}

# run_one <label> <expect_failure: yes|no>
run_one() {
  local label="$1" expect="$2"
  local out
  out="$(./gradlew --offline :app:testDebugUnitTest \
      --tests "com.monkfitness.app.domain.usecase.*" \
      --tests "com.monkfitness.app.domain.adaptive.integration.*" \
      --tests "com.monkfitness.app.domain.adaptive.engine.*" \
      --tests "com.monkfitness.app.domain.workout.*" \
      --tests "com.monkfitness.app.data.mapper.*" \
      --rerun-tasks 2>&1 | tail -8)"
  local caught="no"
  # A mutation that does not compile is caught too: the suites cannot run against it.
  echo "$out" | grep -qE "BUILD FAILED|^e: " && caught="yes"
  local ok="no"
  if [ "$expect" = "yes" ]; then
    [ "$caught" = "yes" ] && ok="yes"
  else
    [ "$caught" = "no" ] && ok="yes"
  fi
  if [ "$ok" = "yes" ]; then
    PASS=$((PASS + 1))
    echo "RED-OK   [$label] expected_failure=$expect caught=$caught"
  else
    FAIL=$((FAIL + 1))
    FAILED_MUTATIONS+=("$label")
    echo "RED-MISS [$label] expected_failure=$expect caught=$caught"
  fi
}

echo "== RED mutations: each must FAIL the §30 step 12 suites (a rule is only proven if a break is caught) =="

# 1. THE TARGET ITSELF: the selection admits the completion's own opportunity and the past ones.
#
# The scenario's completed opportunity is a *past* one (the user trained it late), so its exclusion and
# the temporal clause are one mutation here and the temporal clause has its own two rows below.
apply "the-selection-admits-the-completed-and-the-past" "$TARGET" \
  "            slot.slotId != completedSlotId &&
                slot.attempts.isEmpty() &&
                slot.isStartable &&
                slot.plannedFor.isAfter(notBefore)" \
  "            slot.attempts.isEmpty() &&
                slot.isStartable"
run_one "the target is the next *future* opportunity and never the completed one (§4)" yes
restore "the-selection-admits-the-completed-and-the-past" "$TARGET"

# 2. THE TEMPORAL CLAUSE: the rule reads the wrong direction of time.
apply "the-temporal-clause-is-inverted" "$TARGET" \
  "                slot.plannedFor.isAfter(notBefore)" \
  "                slot.plannedFor.isBefore(notBefore)"
run_one "a past opportunity is never the adaptive target, however startable it is (§4)" yes
restore "the-temporal-clause-is-inverted" "$TARGET"

# 3. THE TEMPORAL CLAUSE, dropped: status and ownership alone decide what is ahead of the user.
apply "the-temporal-clause-is-dropped" "$TARGET" \
  "                slot.plannedFor.isAfter(notBefore)" \
  "                true"
run_one "status alone cannot decide what is ahead of the user (§4, §25)" yes
restore "the-temporal-clause-is-dropped" "$TARGET"

# 4. STARTABILITY: an opportunity the schedule withdrew, or one already taken, becomes a candidate.
apply "an-opportunity-that-is-no-longer-startable-is-a-candidate" "$TARGET" \
  "                slot.isStartable &&" \
  "                true &&"
run_one "a withdrawn or taken opportunity is never the adaptive target (§4, §19)" yes
restore "an-opportunity-that-is-no-longer-startable-is-a-candidate" "$TARGET"

# 5. THE SNAPSHOT: an opportunity whose presentation is already frozen becomes a candidate.
apply "an-opportunity-whose-snapshot-is-taken-is-a-candidate" "$TARGET" \
  "                slot.attempts.isEmpty() &&" \
  "                true &&"
run_one "an opportunity that already has an attempt cannot consume an adjustment (§16, §19)" yes
restore "an-opportunity-whose-snapshot-is-taken-is-a-candidate" "$TARGET"

# 6. THE PROGRAM BOUNDARY: a decision may name another Program.
apply "the-program-boundary-is-not-checked" "$RUNTIME" \
  "        if (decision.programId != session.programId) {
            return refusal(SessionRefusal.AdaptiveTargetRefusal.ANOTHER_PROGRAM)
        }" \
  "        if (false) {
            return refusal(SessionRefusal.AdaptiveTargetRefusal.ANOTHER_PROGRAM)
        }"
run_one "a decision belongs to the Program whose Session produced it (§4, §16)" yes
restore "the-program-boundary-is-not-checked" "$RUNTIME"

# 7. THE REVISION BOUNDARY: adaptive state is revision-scoped, and a decision may cross it.
apply "the-revision-boundary-is-not-checked" "$RUNTIME" \
  "        if (decision.revisionId != session.revisionId) {
            return refusal(SessionRefusal.AdaptiveTargetRefusal.ANOTHER_REVISION)
        }" \
  "        if (false) {
            return refusal(SessionRefusal.AdaptiveTargetRefusal.ANOTHER_REVISION)
        }"
run_one "a decision is never filed under another revision (§4, §18)" yes
restore "the-revision-boundary-is-not-checked" "$RUNTIME"

# 8. THE COMPLETED SLOT: the P8-era same-slot assumption returns.
apply "the-completed-slot-may-receive-the-decision" "$RUNTIME" \
  "        if (decision.slotId == session.slotId) {
            return refusal(SessionRefusal.AdaptiveTargetRefusal.THE_COMPLETED_SLOT_ITSELF)
        }" \
  "        if (false) {
            return refusal(SessionRefusal.AdaptiveTargetRefusal.THE_COMPLETED_SLOT_ITSELF)
        }"
run_one "the opportunity the completion took can never receive its own adaptation (§4, §16)" yes
restore "the-completed-slot-may-receive-the-decision" "$RUNTIME"

# 9. THE TEMPORAL CLAUSE, at the runtime's own boundary: a decision about a day that has passed is
#    accepted, and only the status of the opportunity is left to speak.
apply "the-temporal-clause-is-dropped-at-the-runtime" "$RUNTIME" \
  "        if (!target.plannedFor.isAfter(decisionDay)) {" \
  "        if (false) {"
run_one "a past opportunity cannot receive the decision, however startable it is (§4)" yes
restore "the-temporal-clause-is-dropped-at-the-runtime" "$RUNTIME"

# 9b. STARTABILITY, at the runtime's own boundary.
apply "a-withdrawn-opportunity-may-receive-the-decision" "$RUNTIME" \
  "        if (!target.isStartable) {
            return refusal(SessionRefusal.AdaptiveTargetRefusal.SLOT_IS_NOT_AHEAD_OF_THE_USER)
        }" \
  "        if (false) {
            return refusal(SessionRefusal.AdaptiveTargetRefusal.SLOT_IS_NOT_AHEAD_OF_THE_USER)
        }"
run_one "a decision about an opportunity that is not ahead of the user is refused (§4)" yes
restore "a-withdrawn-opportunity-may-receive-the-decision" "$RUNTIME"

# 10. §18's FILTER: a refused change is treated as if the guard had said nothing.
apply "the-guard-refusal-is-treated-as-an-ordinary-hold" "$INTEGRATION" \
  "            result.wasFilteredByTheGuard && state != null -> AdaptiveIntegrationOutcome.AdaptiveFiltered(" \
  "            false && state != null -> AdaptiveIntegrationOutcome.AdaptiveFiltered("
run_one "a guard-refused decision is kept as NOT_APPLIED, with its reason (§18)" yes
restore "the-guard-refusal-is-treated-as-an-ordinary-hold" "$INTEGRATION"

# 11. NOTHING DECIDED: a held window is written as if a decision had been taken.
apply "a-held-window-is-recorded-as-a-decision" "$INTEGRATION" \
  "            else -> AdaptiveIntegrationOutcome.NothingToAdapt(reason = result.reason, familyState = state)" \
  "            else -> AdaptiveIntegrationOutcome.AdaptiveFiltered(
                decision = result.decision,
                familyState = state!!,
                reason = ProgramAdaptiveReason.AGGREGATE_LOAD_GUARD
            )"
run_one "a window that changed nothing stores no decision row (§12, §16)" yes
restore "a-held-window-is-recorded-as-a-decision" "$INTEGRATION"

# 12. THE FAMILY'S OWN BOOKKEEPING: a window's verdict no longer advances it.
apply "the-window-bookkeeping-is-not-advanced" "$INTEGRATION" \
  "    private fun advanced(stored: Int?, qualified: Boolean): Int = if (qualified) (stored ?: 0) + 1 else 0" \
  "    private fun advanced(stored: Int?, qualified: Boolean): Int = stored ?: 0"
run_one "a window advances the counts the next window confirms on (§11)" yes
restore "the-window-bookkeeping-is-not-advanced" "$INTEGRATION"

# 13. THE OBSERVATIONS: a partial execution is recorded as a full one.
apply "a-partial-exposure-is-recorded-as-a-full-one" "$INTEGRATION" \
  "                    level = if (completed >= prescribed) ExposureLevel.FULL else ExposureLevel.PARTIAL," \
  "                    level = ExposureLevel.FULL,"
run_one "what was performed is measured against what it was prescribed (§12, §13)" yes
restore "a-partial-exposure-is-recorded-as-a-full-one" "$INTEGRATION"

# 14. THE FAMILY: an exercise no classification knows becomes a family of its own, named after itself.
#
# The mutation is stated where the rule is *observable*. The same fallback in `exposedFamiliesOf`
# alone is an equivalent mutant — the element choice decides which family a decision is about, and it
# would still refuse an element whose family it cannot name — so no test can distinguish it, and
# pretending otherwise would be a row that proves nothing.
apply "the-element-choice-fabricates-a-family-from-the-exercise-id" "$TARGET" \
  "        ?.let { exercise -> classification.familyOf(exercise.exerciseId) }" \
  "        return presentation.exerciseId"
run_one "an unclassified exercise names no family, rather than a fabricated one (§9, §10)" yes
restore "the-element-choice-fabricates-a-family-from-the-exercise-id" "$TARGET"

# 15. SUPERSESSION: the standing adjustment is ignored, so the chain is broken.
apply "the-supersession-link-is-dropped" "$INTEGRATION" \
  "    ): AdjustmentId? = standing
        .filter { it.after.programExerciseId.value == programExerciseId }
        .maxByOrNull { it.createdAt }
        ?.adjustmentId" \
  "    ): AdjustmentId? = null"
run_one "a new decision supersedes the standing adjustment by reference (§16)" yes
restore "the-supersession-link-is-dropped" "$INTEGRATION"

# 16. §13's STORED REASON: the row is written with a constant instead of the engine's answer.
apply "the-stored-reason-is-a-constant" "$MAPPER" \
  "    reason = reason?.name" \
  "    reason = ProgramAdaptiveReason.INSUFFICIENT_EVIDENCE.name"
run_one "the decision's own rule is what is stored, and what a restart reads (§13, §22)" yes
restore "the-stored-reason-is-a-constant" "$MAPPER"

# 17. §27's UNIT: the family's state leg leaves the completion transaction.
apply "the-family-state-leg-is-dropped-from-the-transaction" "$RUNTIME" \
  "            familyState?.let { adaptiveRepository.saveFamilyState(it) }" \
  "            "
run_one "adaptive state is part of the completion unit, not a write beside it (§27)" yes
restore "the-family-state-leg-is-dropped-from-the-transaction" "$RUNTIME"

# 18. CONSUMPTION: a start ignores the adjustments standing for its opportunity.
apply "the-start-ignores-the-standing-adjustments" "$RUNTIME" \
  "        val standing = standingAdjustments(adaptiveRepository.adjustmentsOf(slotId))" \
  "        val standing = emptyList<com.monkfitness.app.domain.adaptive.decision.AdaptiveAdjustment>()"
run_one "the snapshot captures the presentation the adjustments compose (§16, §19)" yes
restore "the-start-ignores-the-standing-adjustments" "$RUNTIME"

# 19. USER CHOICE: an element the user authored or pinned becomes adaptable.
apply "a-user-owned-element-is-adaptable" "$ELEMENT" \
  "        get() = ownership == ProgramElementOwnership.AUTOMATIC" \
  "        get() = true"
run_one "the adaptive stage never changes a pinned or user-authored choice (§15, §18)" yes
restore "a-user-owned-element-is-adaptable" "$ELEMENT"

# 20. §19's MODE: a MANUAL Program becomes adapt-able.
apply "the-manual-mode-gate-is-removed" "$INTEGRATION" \
  "        if (revision.mode == ProgramMode.MANUAL) return gap(AdaptiveInputGap.PROGRAM_MODE_IS_MANUAL)" \
  "        if (false) return gap(AdaptiveInputGap.PROGRAM_MODE_IS_MANUAL)"
run_one "a fully user-authored plan is not adapted at all (§19)" yes
restore "the-manual-mode-gate-is-removed" "$INTEGRATION"

# 21. THE LEGACY BOUNDARY: the composition root hands the target integration the Stage-1 adapter.
#
# This mutation is stated against the *wiring*, and it is caught by the compiler rather than by a test —
# which is the strongest possible form of the claim: the two generations do not share a type either. It
# is included because "the target stage cannot reach the Stage-1 storage" is a rule this stage's brief
# states, and a rule that cannot be expressed is a rule nobody can check.
apply "the-target-integration-is-wired-to-the-stage-one-adapter" "$CONTAINER" \
  "        adaptiveRepository = programAdaptiveRepository," \
  "        adaptiveRepository = adaptiveRepository,"
run_one "the target stage cannot be handed the Stage-1 adaptive adapter (§30 step 11, step 15)" yes
restore "the-target-integration-is-wired-to-the-stage-one-adapter" "$CONTAINER"

# THE CONTROL: the un-mutated tree must be GREEN, or the rows above are noise.
run_one "control: the un-mutated tree stays GREEN" no

echo
echo "== restoration =="
md5sum -c "$TMP/before.md5"
restored=$?
if [ "$restored" -eq 0 ]; then
  echo "every mutated production source restored byte-identically"
else
  echo "RESTORATION FAILED — a mutated source is not the reviewed byte"
fi

echo
echo "== result =="
echo "caught: $PASS"
echo "missed: $FAIL"
if [ "$FAIL" -ne 0 ]; then
  printf 'missed mutations: %s\n' "${FAILED_MUTATIONS[*]}"
fi
echo
echo "Not expressed as a mutation, because no single line carries them (the suites assert them"
echo "structurally, and this script says so rather than pretending otherwise):"
echo "  * \"the integration writes nothing\" — it holds no transaction runner, so a write is not"
echo "    expressible: it is asserted by the pass-alone census test and by the architecture suite."
echo "  * \"the engine stays pure\" — asserted by the engine's own architecture suite (no collaborator,"
echo "    no clock, no random source, no id source in any engine source)."
echo "  * \"the integration reads the clock once per pass\" — asserted structurally by the architecture"
echo "    suite's count of clock.now() in the integration source."

[ "$restored" -eq 0 ] && [ "$FAIL" -eq 0 ]
