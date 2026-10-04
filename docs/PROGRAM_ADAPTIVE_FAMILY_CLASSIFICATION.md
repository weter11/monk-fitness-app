# P30 — Production Exercise→Family Classification

**Stage:** §30 step 12 follow-up. **Base:** `b471f34` (the P29 merge).
**Scope:** close §9's `NO_FAMILY_CLASSIFICATION` gap in production, and nothing else.

---

## 1. What this stage is

§30 step 12 recorded **two** facts production did not have, and wired an explicit *"nothing is declared"*
value for each:

```text
no persisted family ladder      → NoDeclaredProgression          (§15's progression relations)
no persisted exercise→family map → NoExerciseFamilyClassification (§9's family membership)
```

Both statements were true **of the target schema** (`§23`) and false **of the app**. Every exercise the
app ships already states its own family as a field. P30 supplies the classification by reading that
field, which moves production **one gap later**:

```text
before P30   every adaptive pass → NO_FAMILY_CLASSIFICATION
after  P30   every adaptive pass → NO_DECLARED_PROGRESSION_RELATION
```

No family is adapted in production, and nothing is fabricated to make one look adaptable.

### Stated explicitly, as this stage's boundary

* **P30 closes exactly one gap: §9's exercise→family classification.** That is the whole of it.
* **The progression relation remains absent.** No ladder is declared for any family, so
  `NO_DECLARED_PROGRESSION_RELATION` is what every production pass reports. Supplying it is a *different*
  artefact — a persisted family ladder — and is outside this stage.
* **`adaptivePreferredExerciseIds` and `recovery` remain neutral exactly as P29 concluded.** P30 read a
  catalogue field on the *adaptive* side; it read no preference, added no ranking, and derived no recovery
  context. `docs/PROGRAM_GENERATION_ADAPTIVE_HISTORY.md` and `docs/PROGRAM_GENERATION_CONTEXT.md` are
  unchanged in substance, and the P29 document now carries a short *"still true after P30"* note so the
  neutral conclusion stays discoverable from the stage that established it.
* **No adaptive decision is fabricated merely because family classification now exists.** The pass reaches
  the classification, names the family, finds no ladder declared for it, and records the typed gap.

---

## 2. The audit that made this legitimate (and what it would have cost to skip it)

The brief required the semantic identity to be *established before implementation*, and to stop rather
than invent a mapping if it failed. Three findings, each measured on the production source:

**1. The identity is identical, not similar.** `Exercise.familyId` is the family the app already groups
exercises by in its own library UI (`MainViewModel` builds its family sections from it), the family
`AdaptiveTarget.Family` names, and the family a `ProgramProgressionRelation` is keyed by. There is **no
second family vocabulary in the tree to reconcile with**.

**2. It is a stored field, never derived.** All 66 `WorkoutGenerator.allExercises` entries pass their
family as the **second positional argument** to `baseRepExercise` / `baseTimerExercise`. Nothing computes
it from the id, the category, the subcategory, the training-style map or a name.

**3. The catalogue's two vocabularies are already one vocabulary.** The 28 distinct family ids in the
exercise entries are **exactly** the 28 ids the catalogue's own `families` list declares — zero
declared-but-unused, zero used-but-undeclared:

```text
declared but unused: []
used but undeclared: []
```

**Why this matters, concretely.** If the audit had found that the app's family grouping and the engine's
family identity were *different concepts*, the only honest outcomes would have been to report the
mismatch or to persist a mapping — and both are ruled out by the hard freeze (no new table, no migration).
The audit is what turned "wire up a classification" from an act of faith into a statement with three
independent pieces of evidence behind it.

**What would have been wrong, and would still compile.** Deriving the family from `ExerciseCategory`
would pass *every* behavioural test in this repository, because the catalogue was authored with categories
and families in broad agreement. Four of the ten RED rows exist precisely because that mistake is
invisible to review and to tests alike.

---

## 3. Request and composition shape

**One new production file**, one read-only port implementation:

```kotlin
class CatalogExerciseFamilyClassification : ExerciseFamilyClassification {
    private val familyByExerciseId: Map<String, String> =
        WorkoutGenerator().getExerciseLibrary()
            .associate { exercise -> exercise.id to exercise.familyId }

    override fun familyOf(exerciseId: String): String? = familyByExerciseId[exerciseId]
}
```

Deliberate choices, each with a reason:

* **No equipment filter.** `getExerciseLibrary()` with no filter returns the whole catalogue
  (`isAccessibleWith` holds for an empty available set), and a family is a property of the exercise's
  *definition* — whether the user happens to own a bar cannot decide whether `pullups` belongs to the
  `pullups` family. This is the same read `ProgramExerciseLibrary` already takes.
* **`associate`, not a filtered map.** Order-independent over distinct ids, so the answer is deterministic
  and a later catalogue revision changes a family's membership only by editing the entry that states it.
* **It exposes nothing else.** No `Exercise`, no name, no equipment, no animation id — so there is nothing
  here for an export to copy and nothing to write back into a plan. `ProgramExerciseLibrary` holds its ids
  under the same discipline.

**Wiring** — `AppContainer` only, one node, placed **outside every existing container slice** (all six
`substringAfter("val …")` gates were enumerated first; a node dropped between two boundaries silently
widens a neighbour's scan):

```kotlin
val catalogExerciseFamilyClassification: CatalogExerciseFamilyClassification =
    CatalogExerciseFamilyClassification()

val programAdaptiveIntegration: ProgramAdaptiveIntegration = ProgramAdaptiveIntegration(
    …
    relations = NoDeclaredProgression,                        // unchanged
    classification = catalogExerciseFamilyClassification,      // P30
    …
)
```

**Nothing else changed shape.** `GenerationPreferences` is untouched; `adaptivePreferredExerciseIds` and
`recovery` remain neutral exactly as P29 concluded; no generation-package file changed; no Focus Planner
or Exercise Selector change; no ladder; no recovery aggregation; no new adaptive semantics.

### Catalogue ownership

The catalogue has **not** gained a second owner. P30 is now the **fifth** reader of
`WorkoutGenerator.getExerciseLibrary()` — after the legacy engine, the settings read-back,
`ProgramExerciseLibrary` and `MainViewModel`'s option list, and P23's generation boundary.

That closed list is a pinned architecture assertion
(`ProductionGenerationBoundaryArchitectureTest.theBoundaryNamesTheLegacyGeneratorAndOnlyTheBoundaryDoes`)
and P30 **extended** it deliberately rather than relaxing it (§8). The extension is allowed for one
stated reason: §9's membership is a fact the catalogue already states per exercise, so reading that field
is the only honest way to answer it — and the alternative would have been a **second exercise list**, a
far larger sin than a fifth reader of the first one. It is not a second *owner*: the new reader holds a
projection of `familyId` and nothing else, which the behavioural suite asserts.

---

## 4. What this stage explicitly does NOT do

| | |
| --- | --- |
| no progression ladder | `relations = NoDeclaredProgression` is unchanged; P30 fills no ladder |
| no static or default ladder | and none is defined anywhere for a future wiring to pick up by accident |
| no `PilotProgressionProfiles` | the Stage-1 pilot is not consulted (§30 step 11) |
| no new table / DAO / repository / migration | §23's entity set is unchanged |
| no persistence, cache or second catalogue | the projection is built once from a compile-time list |
| no ranking or heuristic | the answer is one stored field |
| no inference | no category, subcategory, training-style, focus or name is consulted |
| no new family vocabulary | the answer space **is** the catalogue's 28 declared families |
| no ViewModel / UI access | the port is reached through the integration, not by a screen |
| no change to `GenerationPreferences` | `adaptivePreferredExerciseIds` and `recovery` stay neutral |
| no generation-package change | `domain/program/generated` is untouched |

`currentExerciseId` in a stored `FamilyProgressionState` is a **fact about the family's bookkeeping**, not
a ladder and not a generation preference. P30 neither reads it as one nor lets it stand in for the
relation that does not exist — asserted by `noLadderIsInventedEvenWithFamilyStatePresent`.

---

## 5. A fixture trap worth recording

§30 step 12's rig classifies `pushup` / `pike_pushup` / `decline_pushup` and declares the family
`push-family`. The shipped catalogue holds `pushups` / `pike_pushups` / `decline_pushups` and declares the
family `pushups`. **Those id spaces do not intersect.**

Nothing before P30 noticed, because nothing before P30 ever asked the catalogue a question about a stored
session. Any fixture that will be validated against the catalogue must use the catalogue's own ids — and
the old rig is left untouched, because dozens of suites are built on it. P30 therefore adds
`CatalogAdaptiveIntegrationRig` on real catalogue ids, which is the only shape in which the claim is
measurable: on the old ids the production classification would classify nothing and the pass would stop
one gap **earlier** than production does.

### And the assertion that was briefly vacuous

The central test initially passed for the wrong reason: the rig's completion and its target opportunity
presented the **same** day, so the exposed family was presented and `NO_DECLARED_PROGRESSION_RELATION`
came back whether or not the classification worked.

The rig now has **two** graph shapes, and the distinction is the point:

* `createSingleFamilyGraph()` — every opportunity presents the push day. The target therefore presents
  the family the completion exposed, which is the only state in which the *relation* gap is reachable at
  all. This is the graph the central assertion is measured on.
* `createGraph()` — past opportunities present the push day, the two future ones present the leg day.
  This is the graph the *gap distinction* is measured on, and it yields
  `NO_EXPOSED_FAMILY_IN_THE_TARGET_SLOT`.

Neither assertion could pass without the other.

---

## 6. Verification

### Claim → test

| # | Claim | Test |
| --- | --- | --- |
| 1 | a known exercise returns its **exact stored** family, for all 66 entries | `CatalogExerciseFamilyClassificationTest.everyKnownExerciseAnswersItsExactStoredFamily` |
| 2 | several exercises in one family resolve **identically** (every multi-member family) | `…severalExercisesInOneFamilyResolveIdentically` |
| 3 | different families stay **distinct** — the pair that separates a grouping from a constant | `…differentFamilyIdsRemainDistinct` |
| 4 | an unknown exercise returns **absence**, never a guess (3 shapes of unknown) | `…anUnknownExerciseReturnsAbsence` |
| 5 | **deterministic** across reads and across independent instances | `…classificationIsDeterministicAcrossReadsAndAcrossInstances` |
| 6 | **source fidelity**: the same `familyId` the generation candidate holds | `…classificationAgreesWithTheGenerationCandidateMetadataForEveryExercise` |
| 7 | a later revision cannot alter historical meaning — zero constructor parameters | `…theFamilyMeaningOfAnIdIsTheStoredFieldAndCarriesNoRevisionOrProgramInput` |
| 8 | **no cross-Program behaviour** — the port answers an id and nothing else | `…theAnswerDependsOnTheExerciseIdAloneAndCarriesNoProgramScope` |
| 9 | **exactly one** production classification source | `…ArchitectureTest.thereIsExactlyOneProductionFamilyClassificationSource` |
| 10 | reads the catalogue fact **only**, positively asserted | `…theSourceReadsTheCatalogueFactAndNothingElse` |
| 11 | **no category / subcategory / training-style inference** (13 banned tokens) | `…noFamilyIsInferredFromCategorySubcategoryOrTrainingStyle` |
| 12 | **no persistence**, no clock, no id source — zero constructor parameters | `…theSourceHasNoPersistenceAndNoCollaboratorOfAnyKind` |
| 13 | **no generation-domain dependency**, both directions | `…theSourceDependsOnThePureDomainAndTheGeneratedDomainDependsOnNeither` |
| 14 | **AppContainer is the only construction site**; no UI source names it | `…appContainerIsTheOnlyConstructionSite` |
| 15 | **no new family vocabulary** — the answer space *is* the 28 declared families | `…noNewFamilyVocabularyIsIntroduced` |
| 16 | **no Stage-1 pilot source** consulted | `…noLegacyStageOneAdaptiveSourceIsConsulted` |
| 17 | classification + **no ladder** ⇒ exactly `NO_DECLARED_PROGRESSION_RELATION` | `…IntegrationTest.withClassificationAndNoLadderTheResultIsExactlyTheMissingProgressionRelation` |
| 18 | **negative control**: without it the earlier gap returns exactly | `…withoutTheClassificationTheEarlierTypedGapIsRestoredExactly` |
| 19 | classification is reached **about real catalogue ids before** the relation refusal | `…theClassificationIsConsultedAboutRealCatalogueIdsBeforeTheRelationRefusal` |
| 20 | **nothing is written**; no family state invented | `…nothingIsWrittenAndNoFamilyStateIsInvented` |
| 21 | no ladder invented even with family state present | `…noLadderIsInventedEvenWithFamilyStatePresent` |
| 22 | **Program A history never enters Program B** | `…programAHistoryNeverEntersProgramB` |
| 23 | the unexposed-family gap is a *different* gap and stays one | `…aDayPresentingOnlyAnUnexposedFamilyReportsThatGapNotTheRelationGap` |
| 24 | **P29's signals remain neutral** — whole-table census unchanged | `…theAdaptiveAndRecoverySignalsRemainNeutralExactlyAsP29Concluded` |

### Numbers

| | |
| --- | --- |
| baseline on `b471f34` | 315 classes / 3129 tests / 0 failures / 0 errors / 0 skipped |
| final | **318 classes / 3155 tests / 0 / 0 / 0** |
| new tests | **26** across 3 suites (8 behavioural · 10 architecture · 8 integration) |
| revised, not relaxed | 2 assertions in `ProductionGenerationBoundaryArchitectureTest`, 1 gate widened (§7, §8) |
| RED score | control GREEN · **10 caught · 0 missed · 0 not-a-catch** · restoration byte-identical |
| construction sites | **1** — `AppContainer.catalogExerciseFamilyClassification`, and nothing else (§6, §14) |

Every number was measured on a fresh `--rerun-tasks` pass with `scripts/test-census.sh` gating on the
Gradle exit code and cross-checking the XML `timestamp` range against the wall clock.

### Gate-by-gate evidence that the new tests *catch*

A gate written alongside its own mutation has not been tested. Each of the ten RED mutations was
independently applied and confirmed to fail the owning suite **before** the suite was run for real, and
four additional probes verified the structural gates:

| Probe | Failing tests |
| --- | --- |
| derive family from category | 10 |
| derive family from subcategory | 11 |
| derive family from the training-style map | 12 |
| hard-coded fallback family | 4 |
| return another exercise's family | 11 |
| return `null` for a known exercise | 14 |
| a **second** port implementation in production | 1 (the exactly-one gate) |
| a **second** construction site, in the use case | 1 (the AppContainer-only gate) |
| typed refusal swapped for a different gap | 4 |
| a **sixth** catalogue reader | 1 (the catalogue-ownership pin) |

---

## 7. The RED mutation suite

`scripts/program-stage30-red-mutations.sh` — 10 rows, control GREEN, **10 caught / 0 missed / 0
not-a-catch**, every mutated production source restored byte-identically (`md5sum -c`).

| # | mutation | owns |
| --- | --- | --- |
| 1 | family derived from the **category** | behavioural |
| 2 | family derived from the **subcategory** | behavioural |
| 3 | family derived from the **training-style map** | behavioural |
| 4 | a hard-coded **fallback** family | behavioural |
| 5 | **another exercise's** family returned | behavioural |
| 6 | `null` for a **known** exercise | behavioural |
| 7 | a **legacy pilot** source consulted | gate |
| 8 | **persistence** injected into the source | gate |
| 9 | production **bypasses** the source | gate |
| 10 | the typed **refusal replaced** by another gap | integration |

### Two rows were rewritten after the first run, and both for the same reason

An oracle that scores its own broken rows is worse than no oracle, so both rewrites are recorded here
rather than quietly folded in.

**Row 9 was charged NOT-A-CATCH on the first run.** The mutation named `NoExerciseFamilyClassification`
without re-importing it — but P30 had *removed* that import precisely because the value stopped being
wired, so the row died at `e: Unresolved reference`. That is a compile error, not evidence about the
rule. It was rewritten as the **complete plausible edit**: swap the wiring *and* put the import back, which
is what a real revert of that wiring would look like.

**Row 9 was then re-aimed from the integration suite to the architecture gate, and this is the more
interesting finding.** With the mutation applied, every *integration* test still passed — because
`CatalogAdaptiveIntegrationRig` builds its own `ProgramAdaptiveIntegration` with its own classification
and **never goes through `AppContainer`**. The rig cannot observe a container wiring at all. Only the gate,
which reads the container's own text, catches it.

A row written against the behavioural suite would have produced a MISSED verdict that indicted the wrong
oracle. The rule being tested here is a *wiring* claim, and a wiring claim belongs to the gate that reads
the wiring.

### A third correction: row 7 could not have named the pilot

The pilot file `PilotProgressionProfiles.kt` **no longer exists** — §30 step 15 deleted the legacy
generation. A row that referenced `com.monkfitness.app.domain.adaptive.pilot.PilotProgressionProfiles`
would have died at `e: unresolved` and been charged NOT-A-CATCH for a reason unrelated to its rule. The
row therefore reintroduces the pilot's **shape** — a local `PILOT_PROGRESSION_PROFILES` table keyed by
exercise — which is what *"consult the legacy source"* looks like when someone reconstructs it rather than
imports it.

That exposed a real weakness in the gate it aimed at: the gate matched `PilotProgressionProfiles` as a
**case-sensitive whole token**, so it would not have caught `PILOT_PROGRESSION_PROFILES` or
`PilotProfile` — a later session's spelling. The gate was **widened to a case-insensitive prefix match**
so it catches the vocabulary rather than one spelling of it. The gate got stronger; the row was not
weakened to fit it.

---

## 8. Pins revised rather than relaxed

Two assertions in `ProductionGenerationBoundaryArchitectureTest` were revised, in the same pass, with the
reason recorded in the assertion's own message:

1. **The catalogue-reader list** gained `CatalogExerciseFamilyClassification`, placed where `sorted()`
   puts it (`Catalog…` before `Production…` — these assertions compare against `readers.sorted()`, so an
   entry appended at the end fails with a diff that reads like a *missing* file when the file is present).
2. **The sweep's coverage floor** rose from `>= 4` to `>= 5`, because the two numbers are the same claim:
   this many readers are *expected*, so fewer means the sweep is broken.

The pin was then verified to **still catch** a sixth reader — extended, not weakened.

No assertion was deleted or made permissive.

---

## 9. Architecture gaps recorded

* **The progression relation is still absent**, and that is now the *only* thing between production and a
  real adaptive decision. `docs/PROGRAM_ADAPTIVE_INTEGRATION.md` names the artefact: a persisted family
  ladder (which variants exist, at which positions, prescribing what). Until it exists, every production
  pass ends in `NO_DECLARED_PROGRESSION_RELATION` and no family is adapted.
* **§9's *other* half is still absent.** P30 closes family *membership*; the persisted set of the
  exercises the user's own configuration enables (`§9`'s `allowed exerciseIds`) is a separate fact and is
  unchanged.
* **`NO_FAMILY_CLASSIFICATION` is now unreachable in production** but remains in the vocabulary and is
  still exercised, by a caller that genuinely has no catalogue. Deleting it would leave
  `NO_DECLARED_PROGRESSION_RELATION` as the only way a pass could report *"not enough is known"* — which
  is exactly the conflation the two-member vocabulary exists to prevent.
* **The family is a `String` in production too.** A future ladder artefact will be keyed by these ids, so
  a rename in the catalogue is a rename in every ladder that references it. That is a deliberate
  consequence of reading the app's existing fact rather than introducing a private vocabulary.

---

## 10. Scope boundary — what P30 does NOT do

No new adaptive decision · no progression ladder · no recovery policy · no ranking heuristic · no
`GenerationPreferences` field · no `GenerationRequest` field · no table · no DAO · no repository · no
migration · no Focus Planner / Exercise Selector / GeneratedPlanner change · no scheduler change · no
session-runtime change · no UI change · no import/export field · no generation-package change · no
`PilotProgressionProfiles`.