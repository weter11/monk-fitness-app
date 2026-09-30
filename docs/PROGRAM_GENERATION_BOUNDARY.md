# Program System — the Production Generation Boundary (PR 23)

Scope: **§30 step 10's production side.** The stage that connects the app's real exercise catalogue to
the pure Generated Planner that already exists, as an explicit boundary:

```text
production exercise catalogue  →  GenerationCandidate<E>  →  GeneratedPlanner
```

Reference architecture: `docs/Monk Fitness — Program System Implementation Blueprint.MD` (cited below
as **§N**). Companion: `docs/PROGRAM_GENERATED_PLANNER.md` (PR 10), which built the planner and left
this boundary unwired by design.

This stage is **the boundary only**. It is not the production Generate flow: no UI, no settings screen,
no Generate button, no persistence of a generated plan, no Room migration, no target schedule
authoring, no adaptive wiring, no legacy `WorkoutGenerator` removal and no cleanup of the old Settings
→ Custom Program path. Those are P24 and later.

---

## 1. What exists now, and what this stage adds

On `main` at `7c310f2` the generated domain is complete and tested: `GenerationRequest`,
`GenerationCandidate`, `GeneratedPlan`, `FocusPlan`, `GenerationPolicy`, `FocusPlanner`,
`ExerciseSelector`, `GeneratedPlanner`, `PlanReconciler`, `ProgramGeneratedEditor`.

None of it was rewritten. This stage adds **two production files** and **two test classes**, and
changes no existing production file.

| file | layer | responsibility |
| --- | --- | --- |
| `domain/usecase/ProductionGenerationBoundary.kt` | application | the catalogue → candidate conversion, the two facts it cannot supply, and the assembled `GenerationRequest` |
| `domain/usecase/ExerciseGenerationFacts.kt` | application | the one open seam: `GenerationFocusSource`, and the prescription dimension rule |

```text
WorkoutGenerator().getExerciseLibrary()   ← the app's 66-entry catalogue, a private val in the legacy engine
        ↓ catalogueOf(exercises, focusSource)
ProductionGenerationCatalogue             ← candidates + the exercises nothing classified
        ↓ generationRequest(focus, schedule, duration, availableEquipment, …)
GenerationRequest<Equipment>              ← the pure input, in a package that has never heard of the catalogue
        ↓ GeneratedPlanner.plan(request)
GeneratedPlan
```

## 2. Where each required fact actually comes from

The brief asked for the **real** source of truth for four facts. Three exist in production; one does
not, and saying so is the stage's central result rather than an inconvenience to route around.

| fact | source of truth | status |
| --- | --- | --- |
| exercise identity, family, training domain, body region | `Exercise.toConfigurationMetadata()` (`data/model/Exercise.kt`) — the app's own derived metadata over the exercise's own fields, already used by adaptive validation | **exists** |
| required equipment | `Exercise.requiredEquipment`, normalised at catalogue construction (`Equipment.NONE` is dropped by the generator's factories) | **exists** |
| `PrescriptionDimension` | `Exercise.isTimerBased` → `TIME_BASED`, else `REP_BASED` — the same rule `ProgramsController.addDraftElement` already applies when a user adds an exercise by hand | **exists** |
| focus membership | **nowhere in production** | **gap — supplied through an explicit port, never inferred** |

### The focus-membership gap, in full

`Focus` (§8) is `PUSH | PULL | LEGS | CORE | MOBILITY | POSTURE | CONDITIONING`. The catalogue holds
three other vocabularies, and **all three overlap the focus vocabulary**, which is precisely why the
inference is refused rather than merely avoided:

| catalogue vocabulary | members | overlap with `Focus` |
| --- | --- | --- |
| `ExerciseCategory` | `STRENGTH`, `MOBILITY`, `STRETCHING`, `POSTURE` | `MOBILITY`, `POSTURE` |
| `ExerciseSubCategory` (body region) | `SHOULDERS`, `SPINE`, `HIPS`, `LEGS`, `CORE`, `FULL_BODY`, `HYPERLORDOSIS` | `LEGS`, `CORE` |
| `exerciseToFamiliesMap` values (`ExerciseCategoryFilter`) | `CALISTHENICS`, `MOBILITY`, `LOWER_BACK`, `NECK`, `SHAOLIN`, … (14 names) | `MOBILITY` |

So four of the seven focuses are names the catalogue happens to use, and three (`PUSH`, `PULL`,
`CONDITIONING`) are names it never uses at all — no lookup recovers them, in either direction. And the
overlap is ambiguous even where it exists: `MOBILITY` as a category covers stretching, mobility *and*
posture work, which is not the single focus a generated plan allocates to it. Mapping any of the three
onto `Focus` would be inventing a training fact the data does not contain, which is the same gap
`ProgramsController.generateDraft` already reports as `GENERATION_UNAVAILABLE` and the same
`NoExerciseFamilyClassification` the adaptive side recorded.

**The decision:** membership is an explicit input (`GenerationFocusSource`, one method), and an
exercise the source does not state is **named** in `unclassifiedExerciseIds`. Specifically, the
boundary never:

* substitutes the whole focus vocabulary for a missing answer (`Focus.entries`, or
  `FocusPlan.Balanced.eligibleFocuses`, which is the same claim under another name);
* substitutes an empty set — `GenerationCandidate` refuses that at construction, and that refusal is
  correct per its own KDoc;
* derives membership from the category, the sub-category or the training-style map — all three
  identifiers are banned from the boundary's sources **by name**, and the gate that does so also reads
  the real catalogue to prove the vocabularies really are different.

**The consequence, stated plainly:** with no classification source supplied, the catalogue classifies
nothing, the boundary yields **zero** candidates and **all 66** exercises unclassified, and
`generationRequest` **refuses** rather than assembling a request whose plan would be empty. That is the
state P24 inherits. It is the honest one — it says the app states no focus classification yet, rather
than presenting a plan built from a guess.

### Equipment: forwarded, never filtered

The user's available equipment goes into `GenerationRequest` exactly as the caller states it, with **no**
normalisation. This is a deliberate refusal to inherit the legacy `isAccessibleWith` reading, in which
an empty set means *"no equipment was configured, so constrain nothing"*. Generation's reading is
strict: an empty available set means the user has nothing, so only exercises requiring nothing are
selectable. Reusing the legacy rule would silently offer bar and band exercises to a user who declared
no equipment — exactly the hidden default the brief forbids. RED row 11 proves the gate fires on it.

## 3. Why the boundary is in `domain/usecase`

The generated package may not import `android`, `androidx`, the data layer, the UI or a legacy engine
(§25, §30 step 10), and `ProgramGeneratedArchitectureTest` enforces that by name. The production
catalogue is an Android-flavoured data model (`@StringRes`, `@DrawableRes`, an equipment enum) held in
a `private val` **inside the legacy `WorkoutGenerator`**. So the two halves cannot meet anywhere but
the application layer.

The legacy `WorkoutGenerator` is read **here and only here**, as the app's single current source of
exercise truth — the same justification `ProgramExerciseLibrary` records for its own read of the same
list. That is the allowance the brief permits, and it is a *location*, not an exemption list: the
gate asserts a **closed list of four readers** of `getExerciseLibrary` in the whole production tree, so
a fifth reader is a second owner of the catalogue and fails.

Removing the legacy engine is a later stage's removal. This stage only declines to let the generator
depend on it.

## 4. What this stage does not do

* **No generation UI, no settings screen, no Generate button** — the production Generate use case is P24.
* **No persistence of a generated plan, no Room migration, no schema change.**
* **No target schedule authoring, no adaptive-generation wiring.**
* **No removal of the legacy `WorkoutGenerator`** and no cleanup of the old Settings → Custom Program
  flow. `ProgramsController.generateDraft` still reports `GENERATION_UNAVAILABLE`, and that is now
  *accurate*: the catalogue states no focus classification, and P23 is the machinery that makes that
  answer reportable rather than invisible.
* **No wiring into the composition root.** §26: a graph node exists because something needs it, and
  nothing consumes the boundary yet. The gate asserts a cardinality of **zero** consumers, which is the
  pin P24 inverts.
* **No change to any existing production file** — `git diff --stat` against the base shows two added
  files and nothing modified.

## 5. Architecture gaps recorded

| gap | where it is recorded | owner |
| --- | --- | --- |
| No exercise→focus classification exists in production, so generation cannot yet produce a plan. | `ProductionGenerationBoundary` KDoc; `ProgramsController.generateDraft`'s `GENERATION_UNAVAILABLE`; the boundary's closed consumer list | the training taxonomy's owner — a curated production table or a user-facing Goals & Focus editor. P24 or later. |
| `GenerationRequest`'s `adaptivePreferredExerciseIds`, `recentExerciseIds`, `recentExposureByFocus`, `recentLoadByFocus` and `recovery` have **no production source** on this boundary; `generationRequest` forwards them as stated inputs and this stage computes none of them. | `generationRequest`'s KDoc | the Adaptive integration stage (§30 step 12), which already resolves signals for the legacy path. |
| `availableEquipment` in production (`SettingsManager.availableEquipmentFlow`) defaults to an empty set, which under generation's strict reading means *the user has nothing*. | `ProductionGenerationBoundary` KDoc, "Equipment is forwarded, never filtered" | P24: it must decide whether "nothing configured" means *nothing owned* or *unconstrained*, and state it. This stage did not decide it silently. |
| The legacy `WorkoutGenerator` remains the catalogue's owner. | closed-list reader gate | the legacy-removal stage. |

## 6. Verification

### Baseline — pristine `origin/main` at `7c310f2`

Measured with `scripts/test-census.sh` (`:app:cleanTest :app:testDebugUnitTest --rerun-tasks`, XML
parsed, gated on the gradle exit code, XML timestamps checked against the wall clock):

```text
290 classes / 2772 tests / 0 failures / 0 errors / 0 skipped
xml timestamp range 2026-09-30T19:04:49 .. 19:05:19   wall clock 19:05:19
```

### Focused P23 suites

```text
:app:testDebugUnitTest --tests '…ProductionGenerationBoundary*'
16 behavioural + 15 architecture tests, 0F / 0E / 0S
```

The behavioural suite reads the **real** shipped catalogue wherever a claim is about the real one
(`everyShippedExerciseSurvivesTheMappingOfTheRealCatalogue`,
`theShippedCatalogueCarriesBothPrescriptionDimensionsAndNoThird`), so a claim about the app's actual
66 exercises is measured rather than asserted about a fixture.

### Compile / assemble gates

See the final report; all five gates run on the committed bytes.

### RED evidence

`scripts/program-stage23-red-mutations.sh` — fourteen mutations, one per rule above, over
`ProductionGenerationBoundary.kt`, `ExerciseGenerationFacts.kt` and `GenerationRequest.kt`, each
applied alone and re-checked for (a) real code surviving comment stripping, (b) the source actually
changing, and (c) the row being type-correct in the whole tree. The oracle is the P23 behavioural
suite, the P23 architecture gate, **and** `ProgramGeneratedArchitectureTest` +
`SessionRuntimeArchitectureTest`, whose claims P23 touches (the generated domain's reach, and the
application layer's forbidden tokens).

| # | mutation | expected |
| --- | --- | --- |
| 1 | an unclassified exercise is admitted as training the whole `Focus` vocabulary | caught |
| 2 | an unclassified exercise is dropped from the catalogue | caught |
| 3 | focus membership is inferred from `ExerciseCategory` | caught |
| 4 | the candidate identity is re-minted from the family id | caught |
| 5 | the canonical metadata's body region is restated as a constant | caught |
| 6 | required equipment is widened to an empty set | caught |
| 7 | the prescription dimension is guessed (the two branches swapped) | caught |
| 8 | the empty-catalogue refusal is removed | caught |
| 9 | the catalogue's order is rewritten as sorted-by-id | caught |
| 10 | available equipment is normalised the legacy way | caught |
| 11 | the catalogue is filtered by equipment before it is stated | caught |
| 12 | the generated domain declares a reach back to the boundary | caught |
| 13 | the boundary acquires a repository and a clock | caught |
| 14 | the catalogue is grouped by family | caught |
| — | control: the unmutated tree stays GREEN | not caught (as required) |

Rows 1–3 and 8 are the four ways a fabricated classification hides; rows 4–7 are the four facts
themselves; rows 9, 11 and 14 are the catalogue as a *statement about the library* rather than a
selection; row 10 is the inherited legacy semantics; rows 12 and 13 are the two directions of layering.

Two claims are deliberately **not** expressed as mutations, because no single line carries them:

* **"the boundary is not wired and not reached"** — a source of production code that does not exist
  cannot be mutated. It is asserted structurally: the gate sweeps the whole production tree for a
  reference to either new type and requires the closed list to be **empty**, plus a positive check
  that `AppContainer` does not name them, plus a check that `ProgramsController` does not either.
* **"the catalogue's vocabularies are not the focus vocabulary"** — it is a fact about
  `WorkoutGenerator` and `Exercise`, not about this stage's code. The gate reads the **real** catalogue
  and asserts the three vocabularies' contents, their overlaps and the three focuses the catalogue
  never names, so the inference the boundary refuses is falsifiable rather than merely asserted.

## 7. Claim → test

| claim | test |
| --- | --- |
| a production exercise becomes a candidate with its own identity, family, domain and region | `ProductionGenerationBoundaryTest.aProductionExerciseBecomesACandidateCarryingItsOwnIdentityAndMetadata` |
| the metadata is the catalogue's own derived metadata, exhaustive over the category and sub-category | `…theMetadataIsTheCataloguesOwnAndNotARestatement` |
| the prescription dimension is the catalogue's own `isTimerBased` fact, with no third case | `…thePrescriptionDimensionIsTheCataloguesOwnAndHasNoThirdCase` |
| required equipment is passed through, and `Equipment.NONE` is never required | `…requiredEquipmentIsPassedThroughVerbatimAndNeverIncludesNone` |
| every stated focus reaches the candidate, held in canonical order | `…focusMembershipIsStatedByTheCallerAndEveryFocusOfItReaches` |
| an exercise the source does not state is reported unclassified, not admitted | `…anExerciseTheSourceDoesNotStateIsReportedAsUnclassifiedAndIsNotACandidate` |
| an empty focus set is refused, not read as "trains everything" | `…anEmptyFocusSetIsARefusalAndNotAnEveryFocusExercise` |
| a source that states nothing classifies nothing, and says so for every exercise | `…aSourceThatStatesNothingClassifiesNothingAndSaysSoForEveryExercise` |
| the request is the catalogue forwarded into the pure generation input | `…theRequestIsTheCatalogueForwardedIntoThePureGenerationInput` |
| available equipment is forwarded verbatim and the legacy rule is not inherited | `…availableEquipmentIsForwardedVerbatimAndTheLegacyEmptyMeansNothingRuleIsNotInherited` |
| a catalogue that classified nothing is refused, not turned into an empty plan | `…aCatalogueThatClassifiedNothingIsRefusedRatherThanTurnedIntoAnEmptyPlan` |
| preferences and policy reach the request unchanged | `…preferencesAndPolicyReachTheRequestUnchanged` |
| the mapping is deterministic and order-preserving | `…theMappingIsDeterministicAndPreservesTheCataloguesOwnOrder` |
| the whole chain reaches `GeneratedPlanner` and plans the produced candidates | `…theWholeChainReachesTheGeneratedPlannerWithTheProducedCandidates` |
| all 66 shipped exercises survive the mapping of the **real** catalogue | `…everyShippedExerciseSurvivesTheMappingOfTheRealCatalogue` |
| the real catalogue carries both prescription dimensions and no third | `…theShippedCatalogueCarriesBothPrescriptionDimensionsAndNoThird` |
| the boundary is in the application layer, and the pure package gained no file | `ProductionGenerationBoundaryArchitectureTest.theBoundaryLivesInTheApplicationLayerAndNotInThePureGeneratedPackage` |
| no focus is ever substituted for a missing classification | `…noFocusIsEverSubstitutedForAMissingClassification` |
| the unclassified half is carried as a value and neither half is filtered or re-ordered | `…theUnclassifiedExercisesAreCarriedAsAValueAndNotDropped` |
| an empty catalogue is refused | `…anEmptyCatalogueIsRefusedRatherThanPlannedAsNothing` |
| no focus is inferred from the catalogue's three groupings | `…theBoundaryInfersNoFocusFromTheCataloguesOwnGroupings` |
| those groupings really are different vocabularies, read off the real catalogue | `…theCataloguesRealGroupingsAreNotFocusVocabularyAndTheTestReadsThemToProveIt` |
| the generated domain reaches neither the catalogue nor this boundary | `…theGeneratedDomainReachesNeitherTheCatalogueNorThisBoundary` |
| the legacy catalogue has a closed list of four readers | `…theBoundaryNamesTheLegacyGeneratorAndOnlyTheBoundaryDoes` |
| the boundary invokes no planner decision and owns no business rule | `…theBoundaryInvokesNoPlannerDecisionAndOwnsNoBusinessRule` |
| the boundary holds no state and acquires no collaborator | `…theBoundaryHoldsNoStateAndAcquiresNoCollaborator` |
| no ambient time, randomness or persistence reaches it | `…noSourceOfTheBoundaryReadsAnAmbientTimeOrRandomnessOrReachesPersistence` |
| its imports are exactly the app model, the pure domain and the legacy engine | `…theBoundaryImportsTheAppModelAndTheGeneratedDomainAndNothingElse` |
| its compiled shape mentions only JVM and app types | `…theCompiledBoundaryValuesOnlyDependOnJvmAndAppTypes` |
| it is unwired and unreached, as a cardinality of zero | `…theBoundaryIsNotWiredAndNotReachedBecauseNothingConsumesItYet` |
| the candidate and request shapes it composes are the ones §30 step 10 declares | `…theGeneratedDomainStillDeclaresTheCandidateShapeThisBoundaryComposes` |

## 8. Owner decisions

1. **Focus membership is a port, and production has no implementation of it.** The alternative — deriving
   membership from `ExerciseCategory` or `ExerciseSubCategory` — was rejected because four of the seven
   focuses are names those vocabularies already use, which makes the fabrication read as a reasonable
   simplification in review, and because three focuses are names they never use, which makes the mapping
   unrecoverable anyway. Consequence: P24 must either author a classification or keep reporting
   generation as unavailable. **That is an owner decision and it is deliberately not taken here.**
2. **An unclassified exercise is reported, not dropped and not admitted.** Dropping it would make
   "nothing was classified" indistinguishable from "some things were"; admitting it with a default
   would change what the generator trains. The result value therefore carries both halves as separate
   fields.
3. **Equipment is forwarded without the legacy normalisation.** An empty available set means *the user
   owns nothing* under generation, not *constrain nothing*. Whether that is the right reading for a
   `SettingsManager` default of an empty set is P24's call; this stage did not quietly decide it, and
   it is recorded as a gap above.
4. **The boundary holds no collaborators, and is not wired.** `catalogueOf` takes the catalogue as an
   argument precisely so the mapping is a function of two things; the `WorkoutGenerator` read lives in
   one named function beside it. Nothing is wired because nothing consumes it (§26).
5. **The pure package's file census is unchanged.** The gate asserts the exact eight files of
   `domain/program/generated`, so this stage cannot quietly add one.