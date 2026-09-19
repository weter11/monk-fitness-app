#!/usr/bin/env bash
# §30 step 14 — the deliberate RED mutations of the Settings / navigation / Program UI stage.
#
# Each row applies one mutation to a *production* source, runs the focused §30-step-14 suites, and records
# whether the suites caught it. A rule whose mutation does NOT fail a test is a rule the suites do not prove,
# so this file is the evidence that the Program UI's reach into the application layer, the user's planned
# start date, §5's activation opt-in, the export/share boundary, the localised notices, the single navigation
# graph and the legacy-entry replacement are pinned by tests rather than by prose.
#
# Usage: bash scripts/program-14-red-mutations.sh
#
# Requires the toolchain env in the SAME shell — a mis-env'd run fakes BOTH a pass and a miss:
#   export JAVA_HOME=/home/wer/devis/toolchain/jdk-17.0.19+10
#   export ANDROID_HOME=/home/wer/devis/android-sdk
#   export GRADLE_USER_HOME="$(pwd)/.gradle-home"
#
# The mutations are applied and restored by the driver below, one at a time, from a backup of the reviewed
# bytes; the shell then proves the restoration by `md5sum -c`, so a run leaves the tree exactly as it was.

set -u
cd "$(dirname "$0")/.."

if [ -z "${JAVA_HOME:-}" ] || [ -z "${ANDROID_HOME:-}" ] || [ -z "${GRADLE_USER_HOME:-}" ]; then
  echo "ABORTED: export JAVA_HOME, ANDROID_HOME and GRADLE_USER_HOME first — a mis-env'd run proves nothing."
  exit 1
fi

TMP="$(mktemp -d)"
CONTROLLER="app/src/main/java/com/monkfitness/app/ui/programs/ProgramsController.kt"
NOTICE="app/src/main/java/com/monkfitness/app/ui/programs/ProgramNotice.kt"
IMPORT="app/src/main/java/com/monkfitness/app/domain/usecase/ProgramImportService.kt"
ACTIVITY="app/src/main/java/com/monkfitness/app/MainActivity.kt"
VIEWMODEL="app/src/main/java/com/monkfitness/app/viewmodel/MainViewModel.kt"
IMPORT_SCREEN="app/src/main/java/com/monkfitness/app/ui/screens/ProgramImportScreen.kt"
DETAIL_SCREEN="app/src/main/java/com/monkfitness/app/ui/screens/ProgramDetailScreen.kt"

# Only one runner may touch this tree at a time: two would restore each other's mutations.
if [ -e /tmp/p14-red-mutations.lock ]; then
  echo "ABORTED: another run of this script is in flight (/tmp/p14-red-mutations.lock exists)."
  exit 1
fi
echo "$$" > /tmp/p14-red-mutations.lock
trap 'rm -f /tmp/p14-red-mutations.lock' EXIT

# The reviewed bytes, so the restore is provable rather than assumed. Taken BEFORE any mutation is applied.
md5sum "$CONTROLLER" "$NOTICE" "$IMPORT" "$ACTIVITY" "$VIEWMODEL" "$IMPORT_SCREEN" "$DETAIL_SCREEN" \
  > "$TMP/before.md5"

python3 - "$TMP" "$CONTROLLER" "$NOTICE" "$IMPORT" "$ACTIVITY" "$VIEWMODEL" "$IMPORT_SCREEN" "$DETAIL_SCREEN" <<'PYEOF'
"""
The driver: one mutation at a time, with the focused §30-step-14 suites as the oracle.

It pre-flights the tree first — every anchor must be present exactly once and no replacement may already be
there — because a baseline taken on a mutated file would "prove" a restoration of the wrong bytes. Then, per
row: back the file up, apply the mutation, run the focused suites, restore the file, and record whether the
suites failed. The restore is verified by the shell's `md5sum -c` afterwards.
"""
import shutil
import subprocess
import sys

TMP, controller, notice, importer, activity, viewmodel, import_screen, detail_screen = sys.argv[1:9]

FILES = {
    "CONTROLLER": controller,
    "NOTICE": notice,
    "IMPORT": importer,
    "ACTIVITY": activity,
    "VIEWMODEL": viewmodel,
    "IMPORT_SCREEN": import_screen,
    "DETAIL_SCREEN": detail_screen,
}

# (label, file, anchor that must be present exactly once, replacement that must be absent, the rule it breaks)
MUTATIONS = [
    # ---------------------------------------------------------------- the import's date (§2 of the stage)
    ("the-import-ignores-the-chosen-start-date", "CONTROLLER",
     "                plannedStartDate = review.plannedStartDate\n",
     "                plannedStartDate = null\n",
     "the chosen date is the import request's own fact and becomes the Program's plannedStartDate (\u00a72, \u00a727)"),
    ("the-import-request-carries-no-date", "IMPORT",
     "            plannedStartDate = plannedStartDate ?: importedOn(at),\n",
     "            plannedStartDate = importedOn(at),\n",
     "the date travels inside the one creation transaction, not as a second change afterwards (\u00a72, \u00a727)"),
    ("the-import-review-defaults-to-another-day", "CONTROLLER",
     "                            plannedStartDate = today(),\n",
     "                            plannedStartDate = java.time.LocalDate.MIN,\n",
     "the review's default planned start date is today, in the composition root's calendar (\u00a72)"),
    ("the-import-review-prefills-the-checkbox", "CONTROLLER",
     "                            makeActive = false\n                        )\n",
     "                            makeActive = true\n                        )\n",
     "\u00a75's activation checkbox defaults OFF, so an import changes nothing the user did not ask for (\u00a75)"),
    ("the-import-ignores-the-activation-choice", "CONTROLLER",
     "                makeActive = review.makeActive,\n",
     "                makeActive = false,\n",
     "the imported Program becomes active only when the user explicitly asks (\u00a75, \u00a73)"),
    ("the-import-refusal-is-swallowed", "CONTROLLER",
     "            is ProgramTransferResult.Rejected ->\n"
     "                mutableState.update { it.copy(notice = noticeFor(result.rejection)) }\n"
     "\n"
     "            is ProgramTransferResult.Failed ->\n"
     "                mutableState.update { it.copy(notice = ProgramNotice.IMPORT_FAILED) }\n",
     "            is ProgramTransferResult.Rejected ->\n"
     "                Unit\n"
     "\n"
     "            is ProgramTransferResult.Failed ->\n"
     "                Unit\n",
     "an invalid or unreadable file is reported, never turned into a silent no-op (\u00a715, \u00a733)"),

    # ---------------------------------------------------------------- the management actions (§4, \u00a729)
    ("the-delete-does-not-reach-the-service", "CONTROLLER",
     "        lifecycle.deleteProgram(ProgramId(programId))\n",
     "        ProgramOperationResult.Success(ProgramId(programId))\n",
     "Delete goes through the lifecycle service, with \u00a73's fallback and \u00a729's cascade (\u00a76, \u00a716)"),
    ("the-no-op-save-says-it-saved", "CONTROLLER",
     "        is ProgramSaveOutcome.NothingToChange -> ProgramNotice.DRAFT_UNCHANGED\n",
     "        is ProgramSaveOutcome.NothingToChange -> ProgramNotice.DRAFT_SAVED\n",
     "\u00a76's no-op save writes nothing, and says so instead of reporting a save (\u00a715)"),
    ("the-list-is-not-re-read-after-an-action", "CONTROLLER",
     "        if (notice is ProgramNotice.Done) refresh()\n",
     "        if (false) refresh()\n",
     "the UI keeps no second source of truth: every fact it shows is re-read from the service (\u00a73, \u00a721)"),

    # ---------------------------------------------------------------- the Generated boundary (\u00a78)
    ("the-generated-path-fakes-a-plan-it-did-not-produce", "CONTROLLER",
     "        mutableState.update { it.copy(notice = ProgramNotice.GENERATION_UNAVAILABLE) }\n"
     "        return false\n"
     "    }\n",
     "        mutableState.update { it.copy(notice = ProgramNotice.DRAFT_SAVED) }\n"
     "        return true\n"
     "    }\n",
     "no generated plan is fabricated: the missing classification is reported as a capability boundary (\u00a78)"),

    # ---------------------------------------------------------------- the export / share path (\u00a711)
    ("the-share-never-reaches-the-platform", "CONTROLLER",
     "                shareTarget.share(result.value)\n",
     "                Unit\n",
     "the exporter's file is handed to \u00a711's platform boundary, not dropped (\u00a711)"),
    ("the-export-refusal-is-swallowed", "CONTROLLER",
     "            is ProgramTransferResult.Rejected ->\n"
     "                mutableState.update { it.copy(notice = noticeFor(result.rejection)) }\n"
     "\n"
     "            is ProgramTransferResult.Failed ->\n"
     "                mutableState.update { it.copy(notice = ProgramNotice.STORAGE_FAILED) }\n",
     "            is ProgramTransferResult.Rejected ->\n"
     "                Unit\n"
     "\n"
     "            is ProgramTransferResult.Failed ->\n"
     "                Unit\n",
     "a share of a Program that is not stored is refused and reported (\u00a75, \u00a715)"),

    # ---------------------------------------------------------------- the notices are resources (\u00a714)
    ("a-notice-reuses-another-sentence", "NOTICE",
     "        val SELECTED: ProgramNotice = Done(R.string.programs_notice_selected)\n",
     "        val SELECTED: ProgramNotice = Done(R.string.programs_title)\n",
     "every notice has its own string resource, and a declared string nothing shows is a defect (\u00a714)"),

    # ---------------------------------------------------------------- the layers (\u00a716, \u00a733)
    ("the-state-holder-names-the-data-layer", "CONTROLLER",
     "import com.monkfitness.app.domain.program.ProgramRow\n",
     "import com.monkfitness.app.data.repository.ProgramRepository\n"
     "import com.monkfitness.app.domain.program.ProgramRow\n",
     "the state holder calls application services and never names a repository (\u00a716, \u00a725)"),
    ("a-screen-names-a-dao", "IMPORT_SCREEN",
     "import com.monkfitness.app.platform.ProgramDocumentImport\n",
     "import com.monkfitness.app.platform.ProgramDocumentImport\n"
     "import com.monkfitness.app.data.local.ProgramDao\n",
     "\u00a733: a screen writes no DAO and holds no Room type (\u00a716, \u00a725)"),
    ("the-ui-invents-its-own-calendar", "VIEWMODEL",
     "        zone = programGraph.zone\n",
     "        zone = java.time.ZoneId.of(\"UTC\")\n",
     "the import's \"today\" is read through the composition root's own calendar (\u00a726)"),

    # ---------------------------------------------------------------- the navigation (\u00a73, \u00a77)
    ("a-second-nav-controller-appears", "ACTIVITY",
     "    val navController = rememberNavController()\n",
     "    val navController = rememberNavController()\n"
     "    val secondNavController = rememberNavController()\n",
     "\u00a73: the app keeps its single-navigation-graph architecture"),
    ("the-detail-is-no-longer-reachable", "ACTIVITY",
     "                        navController.navigate(MainViewModel.programDetailRoute(programId))\n",
     "                        navController.navigate(MainViewModel.ROUTE_MY_PROGRAMS)\n",
     "a My Programs row opens \u00a722's Program Detail, which the acceptance flow walks"),
    ("the-legacy-callback-opens-the-program-system", "ACTIVITY",
     "                    onOpenCustomProgram = {\n"
     "                        viewModel.openCustomProgramEditor()\n"
     "                        navController.navigate(MainViewModel.ROUTE_CUSTOM_PROGRAM)\n"
     "                    }\n",
     "                    onOpenCustomProgram = {\n"
     "                        viewModel.openCustomProgramEditor()\n"
     "                        navController.navigate(MainViewModel.ROUTE_PROGRAMS)\n"
     "                    }\n",
     "\u00a77: the legacy Settings callback is not the Program System's entry point"),

    # ---------------------------------------------------------------- the remediation's rules
    ("a-refused-delete-is-reported-as-a-completed-one", "CONTROLLER",
     "            is ProgramOperationResult.Refused -> noticeFor(result.reason)\n",
     "            is ProgramOperationResult.Refused -> success\n",
     "a refusal is never classified as a completed operation (\u00a715, \u00a728)"),
    ("the-detail-closes-even-when-the-delete-was-refused", "DETAIL_SCREEN",
     "                                val outcome = controller.delete(current.row.programId)\n"
     "                                if (outcome is ProgramNotice.Done) {\n"
     "                                    controller.closeDetail()\n"
     "                                    onBack()\n"
     "                                }\n",
     "                                controller.delete(current.row.programId)\n"
     "                                controller.closeDetail()\n"
     "                                onBack()\n",
     "\u00a715: the screen leaves only when the delete completed"),
    ("a-scheduler-failure-is-read-as-an-ordinary-absence", "CONTROLLER",
     "                mutableState.update { it.copy(notice = ProgramNotice.STORAGE_FAILED) }\n"
     "                PreviewFacts(nextOpportunity = null, hasNoFutureDate = false, unreadable = true)\n",
     "                PreviewFacts(nextOpportunity = null, hasNoFutureDate = false, unreadable = false)\n",
     "a SYSTEM_FAILURE is never rendered as the ordinary absence of a next workout (\u00a715, \u00a733)"),
    ("the-planned-start-date-reports-a-rename", "CONTROLLER",
     "        action(ProgramNotice.PLANNED_START_DATE_SET) {\n",
     "        action(ProgramNotice.RENAMED) {\n",
     "saving the planned start date says so, rather than reporting a rename (\u00a714)"),
]

FOCUSED = [
    "com.monkfitness.app.ui.programs.*",
    # §30 step 13's architecture suite is the oracle that owns the *transfer* half of the boundary the UI
    # now calls, and its revised rule ("the UI uses these services and constructs none of them") is what
    # two rows above are aimed at.
    "com.monkfitness.app.domain.usecase.ProgramTransferArchitectureTest",
    # §30 step 13's own suite owns the import service's default planned start date — the row that removes
    # the request's date is caught there as well as in this stage's suite, and both are in the oracle.
    "com.monkfitness.app.domain.usecase.ProgramImportServiceTest",
]


def focused_suites_fail():
    command = ["./gradlew", "--offline", ":app:testDebugUnitTest"]
    for pattern in FOCUSED:
        command += ["--tests", pattern]
    command.append("--rerun-tasks")
    result = subprocess.run(command, capture_output=True, text=True)
    output = result.stdout + result.stderr
    tail = "\n".join(output.strip().split("\n")[-6:])
    # A mutation that does not compile is caught too: the suites cannot run against it.
    caught = result.returncode != 0 and ("BUILD FAILED" in output or "\ne: " in output)
    return caught, tail


# ---------------------------------------------------------------- pre-flight
problems = []
for label, key, anchor, replacement, rule in MUTATIONS:
    text = open(FILES[key], encoding="utf-8").read()
    if text.count(anchor) != 1:
        problems.append("%s: the anchor appears %d times in %s: %r" % (label, text.count(anchor), FILES[key], anchor[:70]))
    if replacement in text:
        problems.append("%s: the replacement is already present in %s: %r" % (label, FILES[key], replacement[:70]))

if problems:
    print("PRE-FLIGHT FAILED — refusing to run, because a baseline taken now would prove nothing:")
    for problem in problems:
        print("  * " + problem)
    sys.exit(1)
print("pre-flight ok: every mutation's anchor is present exactly once and no mutation is already applied")
print()

# ---------------------------------------------------------------- the mutations
print("== RED mutations: each must FAIL the §30 step 14 suites (a rule is only proven if a break is caught) ==")
passed = 0
missed = []
for index, (label, key, anchor, replacement, rule) in enumerate(MUTATIONS, start=1):
    path = FILES[key]
    backup = "%s/%02d-%s.bak" % (TMP, index, key)
    shutil.copy(path, backup)
    text = open(path, encoding="utf-8").read()
    open(path, "w", encoding="utf-8").write(text.replace(anchor, replacement, 1))
    caught, tail = focused_suites_fail()
    shutil.copy(backup, path)
    if caught:
        passed += 1
        print("RED-OK   [%02d %s] %s" % (index, label, rule))
    else:
        missed.append(label)
        print("RED-MISS [%02d %s] %s" % (index, label, rule))
        print("         (the suites stayed GREEN; last lines of the run:)")
        for line in tail.split("\n"):
            print("            " + line)

# ---------------------------------------------------------------- the control
print()
caught, tail = focused_suites_fail()
if caught:
    print("CONTROL FAILED — the un-mutated tree is RED, so every row above is noise")
    for line in tail.split("\n"):
        print("    " + line)
    sys.exit(1)
print("RED-OK   [control: the un-mutated tree stays GREEN]")

print()
print("== result ==")
print("caught: %d of %d" % (passed, len(MUTATIONS)))
print("missed: %d" % len(missed))
for label in missed:
    print("  * " + label)
print()
print("Not expressed as a mutation, because no single line carries them (the suites assert them")
print("structurally, and this script says so rather than pretending otherwise):")
print('  * "no screen reaches a repository or the transfer codec" — the architecture suite scans every UI')
print("    source for those tokens, so the *shape* is asserted directly; the two rows above break it at the")
print("    two places a real regression would start (the state holder and a screen).")
print('  * "the import stays one transaction" — no line of the UI carries it: the unit is ProgramRepository\'s')
print("    own transaction, and this stage's suite plants a failure at the opportunities leg and counts every")
print("    table. A mutation of the UI cannot weaken it, which is the point.")
print('  * "the Program System is no longer a hidden Settings path" — measured by the navigation suite against')
print("    the composed calls, not by one line; the legacy-callback row above is the closest single-line form.")
print('  * "a refused delete leaves nothing closed" — two rows carry it: the classification inside the')
print("    controller (a refusal must not read as `Done`) and the screen's own guard, which the architecture")
print("    suite asserts against the source because this project has no Compose test harness.")

sys.exit(1 if missed else 0)
PYEOF
driver=$?

echo
echo "== restoration =="
md5sum -c "$TMP/before.md5"
restored=$?
if [ "$restored" -eq 0 ]; then
  echo "every mutated production source restored byte-identically"
else
  echo "RESTORATION FAILED — a mutated source is not the reviewed byte"
fi

[ "$restored" -eq 0 ] && [ "$driver" -eq 0 ]
