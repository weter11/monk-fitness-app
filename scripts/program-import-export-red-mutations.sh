#!/usr/bin/env bash
# §30 step 13 — the deliberate RED mutations of the Import / Export / Share stage.
#
# Each row applies one mutation to a *production* source, runs the focused §30-step-13 suites, and records
# whether the suites caught it. A rule whose mutation does NOT fail a test is a rule the suites do not
# prove, so this file is the evidence that the transfer format's allowlist, its determinism, its validation
# layers, its identity re-minting, its imported-source semantics, its selection default, its atomic creation
# unit, the Scheduler's ownership of the opportunities and the Android share boundary are pinned by tests
# rather than by prose.
#
# Usage: bash scripts/program-import-export-red-mutations.sh
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
JSON="app/src/main/java/com/monkfitness/app/domain/program/transfer/Json.kt"
WRITER="app/src/main/java/com/monkfitness/app/domain/program/transfer/ProgramTransferJson.kt"
DOC="app/src/main/java/com/monkfitness/app/domain/program/transfer/ProgramTransferDocument.kt"
READER="app/src/main/java/com/monkfitness/app/domain/program/transfer/ProgramTransferReader.kt"
VALIDATION="app/src/main/java/com/monkfitness/app/domain/program/transfer/ProgramTransferValidation.kt"
MAPPER="app/src/main/java/com/monkfitness/app/domain/program/transfer/ProgramTransferMapper.kt"
IMPORT="app/src/main/java/com/monkfitness/app/domain/usecase/ProgramImportService.kt"
SCHEDULER="app/src/main/java/com/monkfitness/app/domain/usecase/ProgramScheduler.kt"
REPO="app/src/main/java/com/monkfitness/app/data/repository/ProgramRepository.kt"
SHARE="app/src/main/java/com/monkfitness/app/platform/ProgramShareSheet.kt"

# Only one runner may touch this tree at a time: two would restore each other's mutations.
if [ -e /tmp/p13-red-mutations.lock ]; then
  echo "ABORTED: another run of this script is in flight (/tmp/p13-red-mutations.lock exists)."
  exit 1
fi
echo "$$" > /tmp/p13-red-mutations.lock
trap 'rm -f /tmp/p13-red-mutations.lock' EXIT

# The reviewed bytes, so the restore is provable rather than assumed. Taken BEFORE any mutation is applied.
md5sum "$JSON" "$WRITER" "$DOC" "$READER" "$VALIDATION" "$MAPPER" "$IMPORT" "$SCHEDULER" "$REPO" "$SHARE" \
  > "$TMP/before.md5"

python3 - "$TMP" "$JSON" "$WRITER" "$DOC" "$READER" "$VALIDATION" "$MAPPER" "$IMPORT" "$SCHEDULER" "$REPO" "$SHARE" <<'PYEOF'
"""
The driver: one mutation at a time, with the focused suites as the oracle.

It pre-flights the tree first — every anchor must be present exactly once and no replacement may already be
there — because a baseline taken on a mutated file would "prove" a restoration of the wrong bytes. Then, per
row: back the file up, apply the mutation, run the focused suites, restore the file, and record whether the
suites failed. The restore is verified by the shell's `md5sum -c` afterwards.
"""
import shutil
import subprocess
import sys

TMP, json_f, writer, doc, reader, validation, mapper, importer, scheduler, repository, share = sys.argv[1:12]

FILES = {
    "JSON": json_f,
    "WRITER": writer,
    "DOC": doc,
    "READER": reader,
    "VALIDATION": validation,
    "MAPPER": mapper,
    "IMPORT": importer,
    "SCHEDULER": scheduler,
    "REPO": repository,
    "SHARE": share,
}

# (label, file, anchor that must be present exactly once, replacement that must be absent, the rule it breaks)
MUTATIONS = [
    ("the-export-carries-the-programs-identity", "MAPPER", "            name = program.name,", "            name = program.programId.value,",
     "the exported file identifies no Program (\u00a72, \u00a722)"),
    ("the-export-carries-the-programs-lifecycle", "MAPPER", "            description = program.description,", "            description = program.lifecycleStatus.name,",
     "no lifecycle state travels in a shared file (\u00a72)"),
    ("the-export-drops-a-part-of-the-plan", "MAPPER", "                days = revision.days.map { day ->", "                days = revision.days.take(1).map { day ->",
     "the revision's whole plan is carried (\u00a717)"),
    ("the-export-corrupts-a-prescription", "MAPPER", "                                    perSetTargets = element.prescription.perSetTargets", "                                    perSetTargets = element.prescription.perSetTargets.sorted()",
     "prescriptions round-trip losslessly (\u00a710)"),
    ("the-export-loses-the-focus", "MAPPER", "                focus = transferOf(revision.focus),", "                focus = FocusTransfer.Balanced,",
     "goals/focus round-trip losslessly (\u00a78)"),
    ("the-export-loses-the-schedule-form", "DOC", "    is ProgramSchedule.FixedWeekdays ->\n        ScheduleTransfer.FixedWeekdays(schedule.weekdays.sortedBy { day -> day.value })", "    is ProgramSchedule.FixedWeekdays ->\n        ScheduleTransfer.FlexiblePerWeek(schedule.weekdays.size)",
     "the schedule round-trips losslessly (\u00a720)"),
    ("the-export-drops-the-pinning", "MAPPER", "                                isPinned = element.isPinned", "                                isPinned = false",
     "pins survive a transfer (\u00a77)"),
    ("the-writer-emits-another-order", "WRITER", "                \"format\" to quoted(ProgramTransferFormat.MARKER),\n                \"formatVersion\" to document.formatVersion.toString(),", "                \"formatVersion\" to document.formatVersion.toString(),\n                \"format\" to quoted(ProgramTransferFormat.MARKER),",
     "the written document is the format's own shape, byte for byte (\u00a711)"),
    ("the-reader-skips-the-version-check", "READER", "        if (version.value != ProgramTransferFormat.VERSION) {", "        if (false) {",
     "an unsupported formatVersion is rejected (\u00a75)"),
    ("the-reader-accepts-a-missing-version", "READER", "        if (marker.isNotEmpty() || version.value == null) {", "        if (marker.isNotEmpty()) {",
     "a missing formatVersion is a required-field finding (\u00a75)"),
    ("the-reader-accepts-a-trailing-comma", "JSON", "        if (fields.isEmpty() && here.peek() == '}') {", "        if (here.peek() == '}') {",
     "a trailing comma is not JSON and is refused where it is read (\u00a75, \u00a714)"),
    ("the-reader-accepts-a-duplicate-field", "JSON", "        if (fields.any { it.name == name }) {", "        if (false) {",
     "a record with two readings is refused (\u00a75, \u00a714)"),
    ("the-reader-ignores-the-size-limit", "READER", "        if (size > ProgramTransferFormat.MAXIMUM_DOCUMENT_BYTES) {", "        if (false) {",
     "a document larger than the format allows is refused (\u00a714)"),
    ("the-reader-repairs-a-fractional-number", "JSON", "        if (next == '.' || next == 'e' || next == 'E') {", "        if (false) {",
     "a fractional number is refused rather than truncated (\u00a714)"),
    ("the-reader-ignores-unknown-fields", "READER", "        .filterNot { name -> name in allowed }", "        .filterNot { name -> false }",
     "the schema is an allowlist: an undefined field is refused (\u00a72, \u00a75)"),
    ("the-importer-skips-the-exercise-check", "IMPORT", "        if (unknown.isNotEmpty()) throw UnknownDocumentExercises(unknown)", "        if (false) throw UnknownDocumentExercises(unknown)",
     "an unknown exerciseId is rejected and nothing is invented (\u00a75)"),
    ("the-importer-skips-semantic-validation", "IMPORT", "        if (issues.isNotEmpty()) throw SemanticViolation(issues)", "        if (false) throw SemanticViolation(issues)",
     "semantic validation runs before anything is written (\u00a76)"),
    ("the-importer-skips-the-domains-own-validation", "IMPORT", "        if (findings.isNotEmpty()) {", "        if (false) {",
     "the domain's own draft validation is the gate \u00a76 asks for"),
    ("the-validator-repairs-a-rest-day", "VALIDATION", "        val restIssue = if (day.type == ProgramDayType.REST && day.exercises.isNotEmpty()) {", "        val restIssue = if (false) {",
     "a REST day that prescribes something is refused, not tidied (\u00a720)"),
    ("the-validator-accepts-a-sum-that-is-not-a-hundred", "VALIDATION", "                total != FocusPlan.FULL_ALLOCATION -> listOf(", "                false -> listOf(",
     "\u00a78's custom percentages must sum to a hundred"),
    ("the-import-reuses-the-drafts-day-identities", "MAPPER", "                day.copy(\n                    programDayId = ProgramDayId(ids.newId()),", "                day.copy(\n                    programDayId = day.programDayId,",
     "every imported day gets a fresh identity (\u00a73)"),
    ("the-import-reuses-the-drafts-occurrence-identities", "MAPPER", "                        element.copy(programExerciseId = ProgramExerciseId(ids.newId()))", "                        element.copy(programExerciseId = element.programExerciseId)",
     "every imported occurrence gets its own identity (\u00a73, \u00a79)"),
    ("an-imported-program-is-marked-standard", "IMPORT", "            source = ProgramSource.IMPORTED,", "            source = ProgramSource.STANDARD,",
     "an imported Program is never the built-in one (\u00a79, \u00a710)"),
    ("the-import-selects-itself", "IMPORT", "            if (makeActive) select(programId)", "            select(programId)",
     "an import is not a selection: the default is off (\u00a79)"),
    ("the-import-ignores-the-explicit-choice", "IMPORT", "            if (makeActive) select(programId)", "            if (false) select(programId)",
     "the explicit opt-in selects the imported Program, through the selection owner (\u00a79)"),
    ("the-import-skips-the-initial-opportunities", "IMPORT", "            programRepository.createProgram(program, revision, slots)", "            programRepository.createProgram(program, revision, emptyList())",
     "the creation unit includes the revision's initial opportunities (\u00a727)"),
    ("the-scheduler-invents-its-own-opportunities", "SCHEDULER", "            slots = emptyList(),\n            pauses = emptyList()\n        ).create", "        revision.days.map { day ->\n            WorkoutSlot(\n                slotId = slotIds.newId(),\n                programId = program.programId,\n                revisionId = revision.revisionId,\n                programDayId = day.programDayId,\n                plannedFor = anchor,\n                status = SlotStatus.PLANNED\n            )\n        }",
     "the opportunities of an import are the Scheduler's own decision (\u00a78, \u00a720)"),
    ("a-failed-import-leaves-a-partial-program", "REPO", "        inTransaction {\n            programDao.insertProgram(program.toEntity())", "        run {\n            programDao.insertProgram(program.toEntity())",
     "a failure at any leg of the creation leaves no Program at all (\u00a713, \u00a727)"),
    ("the-share-hands-out-a-file-uri", "SHARE", "        return FileProvider.getUriForFile(context, authority(context.packageName), staged)", "        return Uri.fromFile(staged)",
     "a share carries a content:// URI, never a file:// one (\u00a711)"),
    ("the-share-puts-the-file-name-in-the-text-extra", "SHARE", "            putExtra(Intent.EXTRA_STREAM, contentUri(context, file))", "            putExtra(Intent.EXTRA_TEXT, file.fileName)",
     "the payload is the exported file as a stream, not text pasted anywhere (\u00a711)")
]

FOCUSED = [
    "com.monkfitness.app.domain.program.transfer.*",
    # The repository primitive's own suite: one row mutates ProgramRepository.createProgram (its transaction
    # is what makes the creation unit atomic for *every* caller), and §30 step 3's suite is the oracle that
    # owns that guarantee. Leaving it out of the focused set is what a first run of this script recorded as a
    # "missed" mutation — the mutation was caught, by a suite the run did not include.
    "com.monkfitness.app.data.repository.ProgramRepositoryTest",
    "com.monkfitness.app.domain.usecase.ProgramExportServiceTest",
    "com.monkfitness.app.domain.usecase.ProgramImportServiceTest",
    "com.monkfitness.app.domain.usecase.ProgramRoundTripTest",
    "com.monkfitness.app.domain.usecase.ProgramTransferArchitectureTest",
    "com.monkfitness.app.platform.ProgramTransferPlatformBoundaryTest",
    "com.monkfitness.app.domain.ProgramDomainPurityTest",
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
print("== RED mutations: each must FAIL the §30 step 13 suites (a rule is only proven if a break is caught) ==")
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
print('  * "the export carries no session, set, statistic, streak or adaptive state" — the exporter holds no')
print("    repository that could produce one and the document type has no field for one, so a leak is not")
print("    expressible as an edit: it is asserted by the constructor-shape and field-census suites, and")
print("    behaviourally by searching the file's own text for the ids the fixture stored.")
print('  * "the importer builds no opportunity" — there is no WorkoutSlot for it to construct: the')
print("    architecture suite forbids the token, and the Scheduler's ownership is what the decision test")
print("    measures.")
print('  * "no UI code writes a DAO" — no screen or ViewModel names this stage yet, which the architecture')
print("    suite asserts directly.")

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
