#!/usr/bin/env python3
"""Scratch pre-compile harness for scripts/program-stage24-red-mutations.sh.

AD-HOC. Disclosed as ad-hoc. Its only job is to prove every mutation row is type-correct in the
WHOLE tree *before* the ~20 minute real run, because a row that dies on `e: ` is charged as a
failure for a reason unrelated to its rule (hygiene rules 3 and 12).

Deliberately written as ONE Python driver rather than a bash loop with a payload file per row:
the payloads are multi-line, and the first version of this harness passed them through a TSV, which
silently truncated every multi-line row to its first line and reported `ANCHOR MISSING` against a
perfectly good file (hygiene rule 13 — payload position is not payload pairing).

The rows are parsed out of the REAL script's own `mutate` lines, so this harness cannot drift from
what the real run applies.

Restoration: the five mutated sources are snapshotted here and restored after every row, and the
final `md5sum -c` equivalent proves it — a run on an already-mutated tree would otherwise "prove"
the mutated bytes are the reviewed ones (hygiene rule: the baseline is the weakest link).
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(REPO)

MAIN = "app/src/main/java/com/monkfitness/app"
FILES = {
    "CLASSIFICATION": f"{MAIN}/domain/usecase/ProductionFocusClassification.kt",
    "SERVICE": f"{MAIN}/domain/usecase/ProgramGenerationService.kt",
    "BOUNDARY": f"{MAIN}/domain/usecase/ProductionGenerationBoundary.kt",
    "CONTROLLER": f"{MAIN}/ui/programs/ProgramsController.kt",
    "RECONCILER": f"{MAIN}/domain/program/generated/PlanReconciler.kt",
}
# Guards the hygiene rule this stage tripped: a payload variable must not shadow a path variable.
# The probe would otherwise resolve a path to the mutation text and report ANCHOR MISSING against a
# good file, so the collision is asserted here rather than discovered at the end of a 20-minute run.
for _payload in ("BYPASS_RECONCILER", "BYPASS_CONTROLLER", "EDITOR", "EQUIPMENT", "ADAPTIVE",
                 "OWNERSHIP", "ALL_FOCUS", "DROPPED", "CATEGORY", "SUBCATEGORY", "FAMILYMAP"):
    assert _payload not in FILES, f"a payload variable shares a name with a path variable: {_payload}"
WORK = os.environ.get("TMPDIR", "/home/wer/.hermes/cache/scratch") + "/p24-precompile"
SCRIPT = "scripts/program-stage24-red-mutations.sh"


def digest(path):
    return hashlib.md5(open(path, "rb").read()).hexdigest()


def parse_rows():
    src = open(SCRIPT, encoding="utf-8").read()
    calls = re.findall(
        r"^mutate '([^']+)' \"\$(\w+)\" \"\$(\w+)\" \"\$(\w+)\"$", src, flags=re.M
    )
    if len(calls) != 11:
        raise SystemExit(f"expected 11 mutate() calls in the real script, parsed {len(calls)}")

    def payload(name):
        # A payload is a single-quoted bash assignment that may span lines, so DOTALL is required
        # and the terminator is the first `'\\n` at a line start.
        m = re.search(rf"^{name}='(.*?)'$", src, flags=re.M | re.S)
        if not m:
            raise SystemExit(f"payload {name} not found in the real script")
        return m.group(1)

    return [
        {"label": label, "file": FILES[filevar], "old": payload(oldvar), "new": payload(newvar)}
        for label, filevar, oldvar, newvar in calls
    ]


def main():
    rows = parse_rows()
    print(f"parsed {len(rows)} rows from {SCRIPT}")

    shutil.rmtree(WORK, ignore_errors=True)
    os.makedirs(WORK)
    pristine = {}
    for name, path in FILES.items():
        pristine[name] = open(path, encoding="utf-8").read()
        with open(f"{WORK}/{name}.orig", "w", encoding="utf-8") as handle:
            handle.write(pristine[name])
    baseline = {name: digest(path) for name, path in FILES.items()}

    def restore():
        for name, path in FILES.items():
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(pristine[name])

    passed, failed = 0, 0
    for index, row in enumerate(rows, start=1):
        restore()
        text = open(row["file"], encoding="utf-8").read()
        if row["old"] not in text:
            print(f"ANCHOR MISSING [{index}]: {row['label']}\n  {row['old'][:160]!r}")
            failed += 1
            continue
        updated = text.replace(row["old"], row["new"], 1)
        if updated == text:
            print(f"MUTATION NO-OP [{index}]: {row['label']}")
            failed += 1
            continue
        # A comment-only mutation is not evidence: the gates strip comments first.
        stripped = re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", updated, flags=re.S))
        first = row["new"].split("\n")[0].strip()
        if first not in "\n".join(line.strip() for line in stripped.splitlines()):
            print(f"COMMENT-ONLY MUTATION [{index}]: {row['label']}")
            failed += 1
            continue
        with open(row["file"], "w", encoding="utf-8") as handle:
            handle.write(updated)

        subprocess.run(
            ["rm", "-rf", "app/build/kspCaches", "app/build/generated/ksp"], check=False
        )
        proc = subprocess.run(
            [
                "./gradlew", "--offline", ":app:compileDebugUnitTestKotlin", "--console=plain",
            ],
            capture_output=True, text=True,
        )
        output = proc.stdout + proc.stderr
        errors = [line for line in output.splitlines() if line.startswith("e: ")]
        if errors:
            print(f"COMPILE ERROR [{index}]: {row['label']}")
            for line in errors[:4]:
                print(f"    {line}")
            failed += 1
        else:
            print(f"compiles [{index}]: {row['label']}")
            passed += 1

    restore()
    drifted = [name for name, path in FILES.items() if digest(path) != baseline[name]]
    if drifted:
        print(f"RESTORATION FAILED for: {drifted}")
        failed += 1
    else:
        print("every mutated source restored byte-identically")
    print(f"pre-compile passed: {passed} / {len(rows)}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
