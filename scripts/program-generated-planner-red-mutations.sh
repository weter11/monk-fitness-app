#!/usr/bin/env bash
# §30 step 10 — the deliberate RED mutations of the Generated Planner / Focus Planner.
#
# Each block applies one mutation to a *production* source, runs the focused generated-planner suite,
# and records whether the suite caught it. A rule whose mutation does NOT fail a test is a rule the
# suite does not prove, so this file is the evidence that determinism, the focus assignment, the
# hard constraints and §7's reconciliation precedence are pinned by tests rather than by prose.
#
# Usage: bash scripts/program-generated-planner-red-mutations.sh
# Requires: the toolchain env (JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME) exported in the SAME shell.
# A mis-env'd run fakes BOTH a pass and a miss, so export first.
#
# The script restores every mutated file from a backup and verifies the restoration by md5 at the end,
# so a run leaves the reviewed bytes exactly as they were.

set -u
cd "$(dirname "$0")/.."

TMP="$(mktemp -d)"
PASS=0
FAIL=0
FAILED_MUTATIONS=()

GENERATED="app/src/main/java/com/monkfitness/app/domain/program/generated"
FOCUS_PLAN="app/src/main/java/com/monkfitness/app/domain/program/FocusPlan.kt"
STRUCTURE="app/src/main/java/com/monkfitness/app/domain/program/ProgramStructure.kt"

SOURCES=(
  "$GENERATED/FocusPlanner.kt"
  "$GENERATED/ExerciseSelector.kt"
  "$GENERATED/GeneratedPlanner.kt"
  "$GENERATED/GenerationPolicy.kt"
  "$GENERATED/GenerationRequest.kt"
  "$GENERATED/PlanReconciler.kt"
  "$GENERATED/ProgramGeneratedEditor.kt"
  "$FOCUS_PLAN"
  "$STRUCTURE"
)

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
assert old in text, f"mutation anchor not found in {path}: {old[:70]}"
open(path, "w", encoding="utf-8").write(text.replace(old, new, 1))
PY
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
      --tests "com.monkfitness.app.domain.program.generated.*" \
      --tests "com.monkfitness.app.domain.program.FocusPlanTest" \
      --tests "com.monkfitness.app.domain.program.ProgramStructureTest" \
      --rerun-tasks 2>&1 | tail -8)"
  local caught="no"
  # A mutation that does not compile is caught too: the suite cannot run against it.
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

echo "== RED mutations: each must FAIL the generated-planner suite (a rule is only proven if a break is caught) =="

# 1. DETERMINISM: the ordering that decides between otherwise equal candidates.
apply "selection-has-no-deterministic-tie-break" "$GENERATED/ExerciseSelector.kt" \
  "                // 6. deterministic tie-break: the canonical id, ascending.
                { candidate: GenerationCandidate<E> -> candidate.exerciseId }" \
  "                // mutated: no tie-break at all, so the caller's list order decides.
                { candidate: GenerationCandidate<E> -> 0 }"
run_one "selection falls back to the caller's collection order (§9: deterministic tie-break)" yes
restore "selection-has-no-deterministic-tie-break" "$GENERATED/ExerciseSelector.kt"

# 2. DETERMINISM: the focus allocation's own tie-break.
apply "focus-allocation-tie-break-removed" "$GENERATED/FocusPlanner.kt" \
  "                compareByDescending<Focus> { deficit(it, assignmentNumber, weights, totalWeight) }
                    .thenBy { it.ordinal }" \
  "                compareByDescending<Focus> { deficit(it, assignmentNumber, weights, totalWeight) }"
run_one "the focus allocation's ties are broken by nothing (§8: deterministic tie-breaks)" yes
restore "focus-allocation-tie-break-removed" "$GENERATED/FocusPlanner.kt"

# 3. FOCUS SHAPE: 1 primary + 0–2 secondary focuses per slot.
apply "secondary-focus-limit-raised" "$GENERATED/GenerationPolicy.kt" \
  "        const val DEFAULT_SECONDARY_FOCUS_LIMIT: Int = MAX_SECONDARY_FOCUSES" \
  "        const val DEFAULT_SECONDARY_FOCUS_LIMIT: Int = 3"
run_one "a slot takes more than §8's two secondary focuses" yes
restore "secondary-focus-limit-raised" "$GENERATED/GenerationPolicy.kt"

# 4. CUSTOM PERCENTAGES: they must sum to exactly 100%.
apply "custom-percentages-not-enforced" "$FOCUS_PLAN" \
  "            require(total == FocusPlan.FULL_ALLOCATION) {" \
  "            require(total >= 0) {"
run_one "custom focus percentages stop summing to 100% (§8)" yes
restore "custom-percentages-not-enforced" "$FOCUS_PLAN"

# 5. PINNED: never automatically changed.
apply "pinned-elements-become-replaceable" "$GENERATED/PlanReconciler.kt" \
  "    private val ProgramExercise.isOwnedByTheUser: Boolean
        get() = isPinned || origin == ProgramExerciseOrigin.USER_AUTHORED" \
  "    private val ProgramExercise.isOwnedByTheUser: Boolean
        get() = false"
run_one "a pinned element is replaced by a regeneration (§7: pinned is the highest level)" yes
restore "pinned-elements-become-replaceable" "$GENERATED/PlanReconciler.kt"

# 6. EXPLICIT USER OVERRIDE: never silently replaced — a level of its own, not the same rule as the pin.
apply "user-authored-elements-are-not-preserved" "$GENERATED/PlanReconciler.kt" \
  "            val preserved = previousElements.filter { it.isOwnedByTheUser }" \
  "            val preserved = previousElements.filter { it.isPinned }"
run_one "an element the user authored is replaced (§7: explicit user override)" yes
restore "user-authored-elements-are-not-preserved" "$GENERATED/PlanReconciler.kt"

# 7. HARD CONSTRAINTS: an exercise the equipment cannot support is never chosen.
apply "equipment-constraint-ignored" "$GENERATED/GenerationRequest.kt" \
  "    fun isUsable(candidate: GenerationCandidate<E>): Boolean =
        availableEquipment.containsAll(candidate.requiredEquipment) && prescribes(candidate.dimension)" \
  "    fun isUsable(candidate: GenerationCandidate<E>): Boolean =
        prescribes(candidate.dimension)"
run_one "an exercise is chosen although the user has none of its equipment (§9)" yes
restore "equipment-constraint-ignored" "$GENERATED/GenerationRequest.kt"

# 8. IDENTITY: a preserved occurrence keeps its identity, and a fresh one gets a fresh identity.
apply "every-generated-element-is-re-identified" "$GENERATED/PlanReconciler.kt" \
  "                    else -> ReconciledElement(
                        element = satisfiedBy,
                        kind = ChangeKind.PRESERVED,
                        level = PreservationLevel.GENERATED
                    )" \
  "                    else -> ReconciledElement(
                        element = element.asGeneratedElement(ids),
                        kind = ChangeKind.PRESERVED,
                        level = PreservationLevel.GENERATED
                    )"
run_one "an unchanged generated plan churns its element identities (§7)" yes
restore "every-generated-element-is-re-identified" "$GENERATED/PlanReconciler.kt"

# 9. RECONCILIATION IS NOT REPLACEMENT: the user's content leads the day.
apply "regeneration-replaces-the-whole-plan" "$GENERATED/PlanReconciler.kt" \
  "            val elements = preserved + built.filterNotNull().map { it.element }" \
  "            val elements = built.filterNotNull().map { it.element }"
run_one "a regeneration replaces the whole plan, user content included (§7)" yes
restore "regeneration-replaces-the-whole-plan" "$GENERATED/PlanReconciler.kt"

# 10. STRUCTURE: the Goals & Focus configuration is structural content (§6).
apply "goal-focus-is-not-structural" "$STRUCTURE" \
  "    ProgramStructureAspect.FOCUS -> focus != base.focus" \
  "    ProgramStructureAspect.FOCUS -> false"
run_one "changing the goal stops creating a revision (§6: goals/focus are structural)" yes
restore "goal-focus-is-not-structural" "$STRUCTURE"

# 11. THE ADAPTIVE BOUNDARY: the planner consumes signals, and computes no policy.
apply "planner-invents-adaptive-policy" "$GENERATED/GeneratedPlanner.kt" \
  "object GeneratedPlanner {" \
  "object GeneratedPlanner {

    /** mutated: the planner reaches for the adaptive engine's own policy value. */
    private val policyOfTheAdaptiveEngine: Class<*> =
        com.monkfitness.app.domain.adaptive.AdaptivePolicy::class.java"
run_one "the planner imports adaptive policy (§30 step 10 is before the Adaptive Engine)" yes
restore "planner-invents-adaptive-policy" "$GENERATED/GeneratedPlanner.kt"

# 12. THE SCHEDULER BOUNDARY: a plan is days, and no date is expressible here.
apply "planner-reaches-for-the-scheduler" "$GENERATED/GeneratedPlanner.kt" \
  "object GeneratedPlanner {" \
  "object GeneratedPlanner {

    /** mutated: the plan is placed on the calendar by the generator itself. */
    private val schedulerOfThisStage: Class<*> =
        com.monkfitness.app.domain.usecase.ProgramScheduler::class.java"
run_one "the planner invokes the scheduler (§20, §30 step 7 keep scheduling the Scheduler's)" yes
restore "planner-reaches-for-the-scheduler" "$GENERATED/GeneratedPlanner.kt"

# 13. LEGACY ISOLATION: the new planner is not wired to the legacy generator.
apply "planner-uses-the-legacy-generator" "$GENERATED/GeneratedPlanner.kt" \
  "object GeneratedPlanner {" \
  "object GeneratedPlanner {

    /** mutated: the new pipeline is quietly served by the legacy engine. */
    private val legacy: Class<*> =
        com.monkfitness.app.domain.usecase.WorkoutGenerator::class.java"
run_one "the new planner is served by the legacy WorkoutGenerator (§30 step 10's fence)" yes
restore "planner-uses-the-legacy-generator" "$GENERATED/GeneratedPlanner.kt"

# 14. DRAFT-ONLY: generation may not gain a persistence collaborator.
apply "generation-gains-a-persistence-collaborator" "$GENERATED/ProgramGeneratedEditor.kt" \
  "class ProgramGeneratedEditor(" \
  "class ProgramGeneratedEditor(
    /** mutated: generation reaches for a repository to persist through. */
    private val planRepository: com.monkfitness.app.data.repository.ProgramPlanRepository? = null,"
run_one "the generated editor gains a repository (§7: Generate only alters a draft)" yes
restore "generation-gains-a-persistence-collaborator" "$GENERATED/ProgramGeneratedEditor.kt"

# 15. Control: the un-mutated tree must stay GREEN (the suite is not failing for nothing).
run_one "unmutated tree stays GREEN (control)" no

echo
echo "== restoring and verifying the reviewed bytes =="
md5sum -c "$TMP/before.md5"

echo
echo "== summary: $PASS caught, $FAIL missed =="
if [ "$FAIL" -gt 0 ]; then
  echo "MISSED: ${FAILED_MUTATIONS[*]}"
  exit 1
fi
echo "every mandated rule is proven by a failing test when broken."
echo
echo "BEHAVIOURAL, NOT SEMANTIC, mutations: two claims are deliberately absent from the list above."
echo "  * 'Generate persists nothing' is not expressible as a mutation: ProgramGeneratedEditor holds"
echo "    no repository, no DAO, no transaction and no Program to write through, so there is no line to"
echo "    break. It is asserted structurally (its declared fields are exactly [draft, ids], and the"
echo "    architecture suite forbids a data-layer import) and behaviourally (ProgramGeneratedEditorTest"
echo "    compares the row count of every table before and after a generate and a regenerate)."
echo "  * 'the planner reads no clock and no random source' is a property of the whole package rather"
echo "    than of one line: the architecture suite scans every generated source for Random, shuffled,"
echo "    currentTimeMillis, LocalDate.now, Instant.now and Clock, so any of them appearing anywhere"
echo "    fails there."
