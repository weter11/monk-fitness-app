#!/usr/bin/env python3
"""Compile each Stage-25 mutation ALONE before the long loop (§8c).

A mutation that does not type-check is charged `NOT A CATCH` by the harness, and a batch compile's
errors are the union of every row's — so each row is compiled on its own tree here and reported
separately. This is a harness, and its own failure mode is silent: the rows must actually be written to
disk, which is asserted by comparing the source before and after.
"""
import os
import re
import shutil
import subprocess
import sys

ROOT = "/home/wer/devis/monk-fitness-app"
SCRIPT = os.path.join(ROOT, "scripts/program-stage25-red-mutations.sh")
WORK = "/home/wer/.hermes/cache/scratch/p25-precompile"

FILES = {
    "RULES": "app/src/main/java/com/monkfitness/app/ui/programs/GoalsFocusAuthoring.kt",
    "SCREEN": "app/src/main/java/com/monkfitness/app/ui/screens/ProgramEditorScreen.kt",
    "CONTROLLER": "app/src/main/java/com/monkfitness/app/ui/programs/ProgramsController.kt",
    "SERVICE": "app/src/main/java/com/monkfitness/app/domain/usecase/ProgramGenerationService.kt",
    "STRUCTURE": "app/src/main/java/com/monkfitness/app/domain/program/ProgramStructure.kt",
}

ENV = dict(os.environ)
ENV.update({
    "JAVA_HOME": "/home/wer/devis/toolchain/jdk-17.0.19+10",
    "ANDROID_HOME": "/home/wer/devis/android-sdk",
    "PATH": "/home/wer/devis/toolchain/jdk-17.0.19+10/bin:" + ENV["PATH"],
    "GRADLE_USER_HOME": os.path.join(ROOT, ".gradle-home"),
})

script = open(SCRIPT, encoding="utf-8").read()
payloads = dict(re.findall(r"^([A-Z_0-9]+)='((?:[^']|'\"'\"')*)'", script, re.M))
payloads = {k: v.replace("'\"'\"'", "'") for k, v in payloads.items()}

rows = []
for line in script.splitlines():
    m = re.match(r"^mutate '(.*?)' \"\$(\w+)\" \"\$(\w+)\" \"\$(\w+)\"", line)
    if m:
        rows.append(m.groups())

if not rows:
    sys.exit("ABORT: no rows parsed — the extraction is broken, not the script")

shutil.rmtree(WORK, ignore_errors=True)
os.makedirs(WORK)
originals = {}
for path in FILES.values():
    full = os.path.join(ROOT, path)
    originals[full] = open(full, encoding="utf-8").read()
    shutil.copy(full, os.path.join(WORK, os.path.basename(path) + ".orig"))


def restore():
    for full, text in originals.items():
        with open(full, "w", encoding="utf-8") as handle:
            handle.write(text)


failures = 0
try:
    for label, fvar, avar, rvar in rows:
        restore()
        path = os.path.join(ROOT, FILES[fvar])
        text = open(path, encoding="utf-8").read()
        old, new = payloads[avar], payloads[rvar]
        if old not in text:
            print("ANCHOR MISSING: %s" % label)
            failures += 1
            continue
        updated = text.replace(old, new, 1)
        if updated == text:
            print("DID NOT APPLY: %s" % label)
            failures += 1
            continue
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(updated)
        applied = open(path, encoding="utf-8").read()
        if applied == text:
            print("NOT WRITTEN TO DISK: %s" % label)
            failures += 1
            continue
        for cache in ("app/build/kspCaches", "app/build/generated/ksp"):
            shutil.rmtree(os.path.join(ROOT, cache), ignore_errors=True)
        run = subprocess.run(
            ["./gradlew", "--offline", ":app:compileDebugKotlin", "--console=plain"],
            cwd=ROOT, env=ENV, capture_output=True, text=True,
        )
        errors = [l for l in (run.stdout + run.stderr).splitlines() if l.startswith("e: ")]
        if errors:
            print("COMPILE ERROR: %s" % label)
            for line in errors[:4]:
                print("   ", line[:200])
            failures += 1
        else:
            print("COMPILES: %s" % label)
finally:
    restore()

print("\nrows: %d, problems: %d" % (len(rows), failures))
sys.exit(1 if failures else 0)