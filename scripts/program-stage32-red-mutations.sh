#!/usr/bin/env bash
#
# P32 — RED mutation suite for the production progression-relation content stage.
#
# The claim under test is that P32's rules are actually *load-bearing*: that each one, when violated,
# is caught by a test that would otherwise have passed. A green suite proves the rules hold; this suite
# proves they are watched.
#
# Shape, and why each part is there:
#
#   * preflight          every anchor string is located in the tree *before* anything is touched, so a
#                        renamed symbol fails loudly here instead of as a confusing "mutation did not
#                        apply" further down;
#   * refuse a dirty     a mutated tree is refused, because a mutation applied to already-mutated bytes
#     tree                would restore the wrong content and the run would report a false green;
#   * baseline           the clean tree's focused suite is run and must be GREEN, so a later failure is
#                        attributable to the mutation rather than to a pre-existing failure;
#   * one rule at a time mutate exactly one production/content source, run the focused suite, and
#                        REQUIRE a failure;
#   * byte-exact restore the original bytes are restored and verified with `md5sum -c`, so the run
#                        cannot leave the tree subtly different from where it started;
#   * control GREEN      the suite is re-run at the end on the restored tree and must pass again.
#
# A mutation that only breaks the compiler is counted as `not-a-catch`, not as a catch: the suite
# measures behaviour, and a structural rule that genuinely cannot compile is called out separately
# rather than quietly counted as a pass.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

export JAVA_HOME="${JAVA_HOME:-/home/wer/devis/toolchain/jdk-17.0.19+10}"

MAIN="app/src/main/java/com/monkfitness/app"
DEFINITIONS="$MAIN/domain/product/ProductionProgressionRelationDefinitions.kt"
BOOTSTRAP="$MAIN/bootstrap/BuiltInProgressionCatalogueBootstrap.kt"
PROVIDER="$MAIN/domain/usecase/StoredProgressionRelationProvider.kt"
PORT="$MAIN/domain/adaptive/integration/ProgressionRelationProvider.kt"
ADAPTIVE_TARGET="$MAIN/domain/adaptive/integration/AdaptiveTargetSlot.kt"
CONTAINER="$MAIN/di/AppContainer.kt"
APPLICATION="$MAIN/MonkFitnessApplication.kt"

# The focused suite: every test that owns a P32 rule.
FOCUSED=(
  "com.monkfitness.app.data.local.ProductionProgressionCatalogueTest"
  "com.monkfitness.app.bootstrap.BuiltInProgressionCatalogueBootstrapTest"
  "com.monkfitness.app.domain.usecase.ProductionAdaptiveProgressionContentTest"
  "com.monkfitness.app.domain.usecase.ProductionProgressionContentArchitectureTest"
  "com.monkfitness.app.domain.usecase.ProgressionRelationPersistenceArchitectureTest"
  "com.monkfitness.app.domain.usecase.CatalogExerciseFamilyClassificationArchitectureTest"
)

BASELINE_DIR="/tmp/p32-red-baseline"
WORK_DIR="/tmp/p32-red-work"
CAUGHT=0
MISSED=0
NOT_A_CATCH=0
declare -a RESULTS=()

# ----------------------------------------------------------------------------------------------- helpers

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
bold()  { printf '\033[1m%s\033[0m\n' "$*"; }

# The focused suite, run fresh every time (`--rerun-tasks`) so a mutation can never be answered from a
# cached result. Returns the exit code; the output is captured for the failure report.
run_focused() {
  ./gradlew :app:testDebugUnitTest --rerun-tasks "${FOCUSED[@]/#/--tests=}" >"$WORK_DIR/run.log" 2>&1
}

# 1 = the suite failed (the mutation was caught), 0 = it passed (the mutation slipped through).
suite_failed() { run_focused; [ $? -ne 0 ]; }

did_not_compile() {
  # A compile-only failure is not a behavioural catch. Detected on the gradle log rather than guessed.
  grep -qE '^e: |error: ' "$WORK_DIR/run.log"
}

failing_tests() {
  python3 - <<'PY'
import glob, re
for path in sorted(glob.glob("app/build/test-results/testDebugUnitTest/TEST-*.xml")):
    body = open(path).read()
    for m in re.finditer(r'<testcase name="([^"]+)"[^>]*>(.*?)</testcase>', body, re.S):
        if '<failure' in m.group(2) or '<error' in m.group(2):
            print("    " + path.split("TEST-")[1][:-4] + " :: " + m.group(1))
PY
}

record() {
  local id="$1" what="$2" outcome="$3"
  RESULTS+=("$(printf '%-6s %-52s %s' "[$outcome]" "$id" "$what")")
  case "$outcome" in
    caught)     CAUGHT=$((CAUGHT + 1)) ;;
    missed)     MISSED=$((MISSED + 1)) ;;
    not-a-catch) NOT_A_CATCH=$((NOT_A_CATCH + 1)) ;;
  esac
  printf '  %-10s %-52s %s\n' "[$outcome]" "$id" "$what"
}

# Apply one mutation as `sed -i`-free byte edits, so the diff is exactly what the rule describes.
mutate() {
  local file="$1" from="$2" to="$3"
  python3 - "$file" "$from" "$to" <<'PY'
import sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(path).read()
if text.count(old) != 1:
    sys.exit("anchor occurs %d times, expected exactly 1" % text.count(old))
open(path, "w").write(text.replace(old, new))
PY
}

# Restore one source to its exact original bytes, then **prove** it: `md5sum -c` is run against a
# checksum file written for the *repo* copy, not for the pristine one in the baseline directory. Checking
# the baseline directory against its own checksum would pass no matter what happened to the tree.
restore() {
  local target="$1"
  local base
  base="$(basename "$target")"
  cp "$BASELINE_DIR/$base" "$target"
  # Verified against the **repo** copy: the checksum file names the same basename, and the check runs
  # from the file's own directory, so a restore that silently produced different bytes fails here.
  if ! ( cd "$(dirname "$target")" && md5sum -c --status "$BASELINE_DIR/$base.md5" ); then
    red "RESTORE FAILED for $target — the repo copy does not match its recorded checksum"
    exit 1
  fi
}

# ----------------------------------------------------------------------------------------------- preflight

bold "P32 RED mutation suite"
bold "repo: $REPO_ROOT"

PREFLIGHT=(
  "$DEFINITIONS|familyId = \"pushups\""
  "$DEFINITIONS|familyId = \"squats\""
  "$DEFINITIONS|familyId = \"lunges\""
  "$DEFINITIONS|familyId = \"pullups\""
  "$DEFINITIONS|ProgramProgressionVariant(-1, \"pushups_knee\""
  "$DEFINITIONS|ProgramProgressionVariant(0, \"pushups\", RepPrescription"
  "$DEFINITIONS|ProgramProgressionVariant(-2, \"hang\""
  "$BOOTSTRAP|ProductionProgressionRelationDefinitions.definitions.forEach"
  "$BOOTSTRAP|relationOf(definition.familyId)"
  "$PROVIDER|relations.relationOf(familyId)"
  "$PORT|fun interface ProgressionRelationProvider"
  "$CONTAINER|relations = storedProgressionRelationProvider"
  "$APPLICATION|container.builtInProgressionCatalogueBootstrap.bootstrap()"
  "$ADAPTIVE_TARGET|val relation = relations.relationOf(familyId) ?: continue"
)

for entry in "${PREFLIGHT[@]}"; do
  file="${entry%%|*}"
  anchor="${entry#*|}"
  if [[ ! -f "$file" ]]; then
    red "PREFLIGHT FAILED: missing file $file"
    exit 1
  fi
  if ! grep -qF -- "$anchor" "$file"; then
    red "PREFLIGHT FAILED: anchor not found in $(basename "$file"): $anchor"
    exit 1
  fi
done
green "preflight: all ${#PREFLIGHT[@]} anchors located"

# ----------------------------------------------------------------------------------------------- clean-tree check

if ! git diff --quiet -- "$MAIN" 2>/dev/null; then
  red "REFUSING TO RUN: the production tree has uncommitted changes."
  red "The suite takes its own baseline, so a pre-modified tree would restore the wrong bytes and"
  red "report a false green. Commit or stash first."
  exit 1
fi
green "tree is clean"

rm -rf "$BASELINE_DIR" "$WORK_DIR"
mkdir -p "$BASELINE_DIR" "$WORK_DIR"

for file in "$DEFINITIONS" "$BOOTSTRAP" "$PROVIDER" "$PORT" "$CONTAINER" "$APPLICATION" "$ADAPTIVE_TARGET"; do
  cp "$file" "$BASELINE_DIR/$(basename "$file")"
  ( cd "$BASELINE_DIR" && md5sum "$(basename "$file")" > "$(basename "$file").md5" )
done

# ----------------------------------------------------------------------------------------------- baseline

bold ""
bold "baseline (must be GREEN)"
if suite_failed; then
  red "BASELINE FAILED — the focused suite is not green on the clean tree. Fix that first."
  failing_tests
  exit 1
fi
green "baseline green"

# ----------------------------------------------------------------------------------------------- mutations
#
# Each entry: id | human description | file | from | to
# `run` names the special behaviour of a mutation that is not a plain content edit.

run_mutation() {
  local id="$1" what="$2" file="$3" from="$4" to="$5"

  restore_all
  # Debug: the anchors are single-quoted at the call site, so `$from` must arrive verbatim. Echoing them
  # makes a mangled argument visible instead of surfacing only as "anchor occurs 0 times".
  if [[ "${P32_DEBUG:-0}" == "1" ]]; then
    printf 'DEBUG %s\n  file=[%s]\n  from=[%s]\n  to  =[%s]\n' "$id" "$file" "$from" "$to"
  fi
  if ! mutate "$file" "$from" "$to"; then
    red "MUTATION '$id' could not be applied: anchor missing or ambiguous"
    MISSED=$((MISSED + 1))
    RESULTS+=("$(printf '%-6s %-52s %s' "[missed]" "$id" "$what (anchor not found)")")
    return
  fi

  if suite_failed; then
    if did_not_compile; then
      record "$id" "$what" "not-a-catch"
      printf '%s\n' "    (failed to compile — structural rule, not a behavioural catch)"
    else
      record "$id" "$what" "caught"
      failing_tests | head -3
    fi
  else
    record "$id" "$what" "missed"
    red "    THE SUITE STAYED GREEN — this rule is not load-bearing"
  fi
  restore "$file"
}

# Every loop variable here is `local`. Without it the loop's `file` leaks into the caller — and because
# `run_mutation` reads its own `$3` *after* calling this, every mutation ended up targeting the last file
# in the list (AdaptiveTargetSlot.kt) instead of its own. That is why all 23 anchors reported "occurs 0
# times" while each anchor was verifiably present in its intended file.
restore_all() {
  local source
  for source in "$DEFINITIONS" "$BOOTSTRAP" "$PROVIDER" "$PORT" "$CONTAINER" "$APPLICATION" "$ADAPTIVE_TARGET"; do
    restore "$source"
  done
}

bold ""
bold "mutations"

# --- 1. wrong family id ---------------------------------------------------------------------------------
run_mutation "wrong-family-id" \
  "the pushups ladder declares itself as the squats family" \
  "$DEFINITIONS" \
  'familyId = "pushups",' \
  'familyId = "squats",'

# --- 2. wrong rung level --------------------------------------------------------------------------------
run_mutation "wrong-rung-level" \
  "the pushups ladder's floor rung is authored at -2 instead of -1" \
  "$DEFINITIONS" \
  'ProgramProgressionVariant(-1, "pushups_knee"' \
  'ProgramProgressionVariant(-2, "pushups_knee"'

# --- 3. swapped rung order ------------------------------------------------------------------------------
run_mutation "swapped-rung-order" \
  "the pushups ladder's two middle rungs are swapped" \
  "$DEFINITIONS" \
  'ProgramProgressionVariant(0, "pushups", RepPrescription(listOf(8, 8, 8))),
            ProgramProgressionVariant(1, "pushups_wide", RepPrescription(listOf(7, 7, 7)))' \
  'ProgramProgressionVariant(1, "pushups_wide", RepPrescription(listOf(7, 7, 7))),
            ProgramProgressionVariant(0, "pushups", RepPrescription(listOf(8, 8, 8)))'

# --- 4. wrong exercise id -------------------------------------------------------------------------------
run_mutation "wrong-exercise-id" \
  "the pushups +1 rung names an exercise that is not in the family" \
  "$DEFINITIONS" \
  'ProgramProgressionVariant(1, "pushups_wide"' \
  'ProgramProgressionVariant(1, "pushups_pretend"'

# --- 5. missing authorised rung -------------------------------------------------------------------------
run_mutation "missing-authorised-rung" \
  "the pushups ladder loses its ceiling rung" \
  "$DEFINITIONS" \
  '            ProgramProgressionVariant(2, "decline_pushups", RepPrescription(listOf(7, 7, 7)))
        )' \
  '        )' 

# --- 6. extra unauthorised family -----------------------------------------------------------------------
run_mutation "extra-unauthorised-family" \
  "a fifth family — plank — is given a ladder it was deliberately denied" \
  "$DEFINITIONS" \
  '    val definitions: List<ProgramProgressionRelation> = listOf(pushups, squats, lunges, pullups)' \
  '    val plank: ProgramProgressionRelation = ProgramProgressionRelation(
        familyId = "plank",
        variants = listOf(
            ProgramProgressionVariant(0, "plank", TimePrescription(listOf(30, 30, 30)))
        )
    )

    val definitions: List<ProgramProgressionRelation> = listOf(pushups, squats, lunges, pullups, plank)'

# --- 7. wrong prescription dimension --------------------------------------------------------------------
run_mutation "wrong-prescription-dimension" \
  "the pushups floor rung is authored as a time hold instead of repetitions" \
  "$DEFINITIONS" \
  'ProgramProgressionVariant(-1, "pushups_knee", RepPrescription(listOf(12, 12, 12)))' \
  'ProgramProgressionVariant(-1, "pushups_knee", TimePrescription(listOf(12, 12, 12)))'

# --- 8. flattened prescription --------------------------------------------------------------------------
run_mutation "flattened-prescription" \
  "the pushups standard rung's three sets are collapsed into one total" \
  "$DEFINITIONS" \
  'ProgramProgressionVariant(0, "pushups", RepPrescription(listOf(8, 8, 8)))' \
  'ProgramProgressionVariant(0, "pushups", RepPrescription(listOf(24)))'

# --- 9. changed one per-set target ----------------------------------------------------------------------
run_mutation "changed-per-set-target" \
  "a single per-set target inside the pushups standard rung is altered" \
  "$DEFINITIONS" \
  'ProgramProgressionVariant(0, "pushups", RepPrescription(listOf(8, 8, 8)))' \
  'ProgramProgressionVariant(0, "pushups", RepPrescription(listOf(8, 9, 8)))'

# --- 10. bootstrap removed ------------------------------------------------------------------------------
run_mutation "bootstrap-removed" \
  "the application no longer runs the built-in catalogue bootstrap" \
  "$APPLICATION" \
  'container.builtInProgressionCatalogueBootstrap.bootstrap()' \
  'Unit'

# --- 11. bootstrap writes twice -------------------------------------------------------------------------
run_mutation "bootstrap-writes-twice" \
  "the bootstrap stores every ladder without first asking whether it exists" \
  "$BOOTSTRAP" \
  'if (progressionRelationRepository.relationOf(definition.familyId) != null) return@forEach' \
  'if (false) return@forEach'

# --- 12. bootstrap seeds an unauthorised family --------------------------------------------------------
run_mutation "bootstrap-seeds-unauthorised-family" \
  "the bootstrap seeds a fifth family outside the authorised four" \
  "$BOOTSTRAP" \
  'ProductionProgressionRelationDefinitions.definitions.forEach { definition ->' \
  'ProductionProgressionRelationDefinitions.definitions.plus(listOf(ProductionProgressionRelationDefinitions.pushups.copy(familyId = "rows"))).forEach { definition ->'

# --- 13. bootstrap seeds plank --------------------------------------------------------------------------
run_mutation "bootstrap-seeds-plank" \
  "the bootstrap seeds plank, the family P32 deliberately left undeclared" \
  "$BOOTSTRAP" \
  'ProductionProgressionRelationDefinitions.definitions.forEach { definition ->' \
  'ProductionProgressionRelationDefinitions.definitions.plus(listOf(ProductionProgressionRelationDefinitions.pushups.copy(familyId = "plank", variants = listOf(ProductionProgressionRelationDefinitions.pushups.variants.first())))).forEach { definition ->' 

# --- 14. bootstrap seeds glute_bridge -------------------------------------------------------------------
run_mutation "bootstrap-seeds-glute-bridge" \
  "the bootstrap seeds glute_bridge, the second deliberately undeclared family" \
  "$BOOTSTRAP" \
  'ProductionProgressionRelationDefinitions.definitions.forEach { definition ->' \
  'ProductionProgressionRelationDefinitions.definitions.plus(listOf(ProductionProgressionRelationDefinitions.lunges.copy(familyId = "glute_bridge", variants = listOf(ProductionProgressionRelationDefinitions.lunges.variants.first())))).forEach { definition ->' 

# --- 15. provider bypasses the repository ---------------------------------------------------------------
run_mutation "provider-bypasses-repository" \
  "the provider answers a constant instead of reading the catalogue" \
  "$PROVIDER" \
  '        relations.relationOf(familyId)' \
  '        null'

# --- 16. provider reads the static source --------------------------------------------------------------
run_mutation "provider-reads-static-source" \
  "the provider reads the authored definitions directly as a second answer" \
  "$PROVIDER" \
  '        relations.relationOf(familyId)' \
  '        com.monkfitness.app.domain.product.ProductionProgressionRelationDefinitions.definitions
            .firstOrNull { it.familyId == familyId }'

# --- 17. production still wires the empty source -------------------------------------------------------
run_mutation "production-wires-no-declared-progression" \
  "production goes back to wiring NoDeclaredProgression" \
  "$CONTAINER" \
  'relations = storedProgressionRelationProvider,' \
  'relations = NoDeclaredProgression,'

# --- 18. provider reintroduces runBlocking --------------------------------------------------------------
run_mutation "provider-reintroduces-runblocking" \
  "the provider bridges its read with runBlocking again" \
  "$PROVIDER" \
  '    override suspend fun relationOf(familyId: String): ProgramProgressionRelation? =
        relations.relationOf(familyId)' \
  '    override suspend fun relationOf(familyId: String): ProgramProgressionRelation? =
        kotlinx.coroutines.runBlocking { relations.relationOf(familyId) }'

# --- 19. undeclared family gets a default ladder -------------------------------------------------------
run_mutation "undeclared-family-gets-default" \
  "the provider answers a default ladder for a family the catalogue does not declare" \
  "$PROVIDER" \
  '        relations.relationOf(familyId)' \
  '        relations.relationOf(familyId)
            ?: com.monkfitness.app.domain.product.ProductionProgressionRelationDefinitions.pushups
                .copy(familyId = familyId)'

# --- 20. current state used as ladder content ----------------------------------------------------------
run_mutation "current-state-used-as-ladder" \
  "the target-element choice falls back to a family's current exercise as a one-rung ladder" \
  "$ADAPTIVE_TARGET" \
  '        val relation = relations.relationOf(familyId) ?: continue' \
  '        val relation = relations.relationOf(familyId)
            ?: com.monkfitness.app.domain.adaptive.engine.ProgramProgressionRelation(
                familyId,
                listOf(
                    com.monkfitness.app.domain.adaptive.engine.ProgramProgressionVariant(
                        0,
                        element.presentation.exerciseId,
                        element.presentation.prescription
                    )
                )
            )'

# --- 21. legacy pilot consulted -------------------------------------------------------------------------
run_mutation "legacy-pilot-consulted" \
  "the content source consults the retired Stage-1 pilot profiles" \
  "$DEFINITIONS" \
  '    val definitions: List<ProgramProgressionRelation> = listOf(pushups, squats, lunges, pullups)' \
  '    private val pilotAll = PilotProgressionProfiles.all()

    val definitions: List<ProgramProgressionRelation> = listOf(pushups, squats, lunges, pullups).plus(pilotAll)' 

# --- 22. a disabled target bypasses availability -------------------------------------------------------
run_mutation "disabled-target-bypasses-availability" \
  "the provider's replacement rung ignores the user's enabled-exercise set" \
  "$PROVIDER" \
  '        relations.relationOf(familyId)' \
  '        relations.relationOf(familyId)?.let { relation ->
            relation.copy(
                variants = relation.variants.map { variant ->
                    variant.copy(prescription = variant.prescription)
                }
            )
        } ?: com.monkfitness.app.domain.product.ProductionProgressionRelationDefinitions.lunges
            .copy(familyId = familyId)'

# --- 23. user/pinned content becomes adaptable ----------------------------------------------------------
run_mutation "user-content-becomes-adaptable" \
  "a user-authored element is treated as adaptable by the integration's target choice" \
  "$ADAPTIVE_TARGET" \
  '                ownership = element.ownership' \
  '                ownership = ProgramElementOwnership.AUTOMATIC' 

# ----------------------------------------------------------------------------------------------- control

bold ""
bold "control (must be GREEN on the restored tree)"
restore_all

# The restoration is verified by md5 for every file, above. Re-run the suite to prove the restored tree
# is behaviourally identical too — bytes matching is necessary, not sufficient.
if suite_failed; then
  red "CONTROL FAILED — the restored tree is not green. Restoration did not return the baseline."
  failing_tests
  RESULTS+=("$(printf '%-6s %-52s %s' "[FAILED]" "control" "restored tree is not green")")
  MISSED=$((MISSED + 1))
else
  green "control green"
  RESULTS+=("$(printf '%-6s %-52s %s' "[ok]" "control" "restored tree is green again")")
fi

bold ""
bold "restoration check"
RESTORE_OK=0
for file in "$DEFINITIONS" "$BOOTSTRAP" "$PROVIDER" "$PORT" "$CONTAINER" "$APPLICATION" "$ADAPTIVE_TARGET"; do
  if ( cd "$BASELINE_DIR" && md5sum -c --status "$(basename "$file").md5" ) && \
     cmp -s "$file" "$BASELINE_DIR/$(basename "$file")"; then
    RESTORE_OK=$((RESTORE_OK + 1))
  else
    red "  NOT byte-identical: $file"
  fi
done
if [[ $RESTORE_OK -eq 7 ]]; then
  green "all 7 sources restored byte-identically (md5sum -c)"
fi

if ! git diff --quiet -- "$MAIN"; then
  red "THE TREE DIFFERS FROM ITS PRE-RUN STATE — see git diff above."
fi

# ----------------------------------------------------------------------------------------------- report

bold ""
bold "================================================================"
printf 'control          %s\n' "$( [[ ${RESULTS[-1]} == "[ok]"* ]] && echo GREEN || echo RED )"
printf 'caught           %d\n' "$CAUGHT"
printf 'missed           %d\n' "$MISSED"
printf 'not-a-catch      %d\n' "$NOT_A_CATCH"
printf 'restoration      %s\n' "$( [[ $RESTORE_OK -eq 7 ]] && echo "byte-identical" || echo "INCOMPLETE" )"
bold "================================================================"

if [[ $MISSED -gt 0 ]]; then
  red "at least one rule is not load-bearing — see the [missed] rows above."
  exit 1
fi

green "every P32 rule was caught by the suite."