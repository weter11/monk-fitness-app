# Program System — the Production Generation Flow (PR 24)

Scope: **§30 step 24.** The stage that makes generation *production-callable*: the exercise library
now states which focuses each exercise trains, and the Generated Editor's `Generate` button runs a
real generation pass instead of reporting that automatic planning is unavailable.

Reference architecture: `docs/Monk Fitness — Program System Implementation Blueprint.MD` (cited below
as **§N**). Companions: `docs/PROGRAM_GENERATION_BOUNDARY.md` (PR 23, the boundary this stage
supplies with a caller) and `docs/PROGRAM_GENERATED_PLANNER.md` (PR 10, the pure planner).

---

## 1. The pipeline as it now runs

```text
WorkoutGenerator().getExerciseLibrary()        the app's shipped catalogue, 66 exercises
        ↓
ProductionFocusClassification                 ← P24: explicit exercise → Focus data
        ↓  (through P23's GenerationFocusSource port)
ProductionGenerationBoundary                  ← P23: catalogue → GenerationCandidate<Equipment>
        ↓
GenerationRequest<Equipment>                  the pure input: focus, schedule, duration,
        ↓                                      candidates, availableEquipment, preferences, policy
GeneratedPlanner                              P10: allocation and selection, pure
        ↓  GeneratedPlan (a value, no identity, no persistence)
ProgramGeneratedEditor → PlanReconciler       P10: reconcile into the draft, preserve what the user owns
        ↓
GeneratedDraftEdit                            the next draft + the plan + the reconciliation report
        ↓
working ProgramEditorDraft                    the ONLY thing a generation pass changes
```

The orchestration that says *in what order* these run lives in one class,
`domain/usecase/ProgramGenerationService.kt`. It is the single production caller of the P23 boundary,
and the UI reaches it by type only — no screen or controller names the planner, the generated editor,
the reconciler or the request.

### Files

| file | layer | responsibility |
| --- | --- | --- |
| `domain/usecase/ProductionFocusClassification.kt` | application | **new** — the explicit exercise → `Focus` table, implementing P23's `GenerationFocusSource` |
| `domain/usecase/ProgramGenerationService.kt` | application | **new** — the one `Generate`/`Regenerate` orchestration, its typed result and its two typed refusals |
| `domain/usecase/ProductionGenerationBoundary.kt` | application | unchanged (P23) — the catalogue → candidate conversion and the assembled request |
| `domain/program/generated/**` | pure domain | **unchanged** — still exactly the eight files PR 10 established |
| `ui/programs/ProgramsController.kt` | UI/application | `generateDraft()` / `regenerateDraft()`, `setDraftFocus` |
| `di/AppContainer.kt` | composition root | wires `programGenerationService` |

No file in `domain/program/generated` was modified. The stage added no file to the pure package, and
`ProgramGenerationFlowArchitectureTest` asserts that file census by name.

---

## 2. The focus classification is authored data

`Focus` (§8) is `PUSH | PULL | LEGS | CORE | MOBILITY | POSTURE | CONDITIONING`. The catalogue holds
three other vocabularies and **none of them is this one**: `ExerciseCategory`
(`STRENGTH|MOBILITY|STRETCHING|POSTURE`), `ExerciseSubCategory` (a body region), and
`exerciseToFamiliesMap`'s training styles. Four of the seven focuses are names those vocabularies
happen to use and three (`PUSH`, `PULL`, `CONDITIONING`) are names they never use — which is why P23
refused to derive membership and left the gap open.

P24 closes the gap from the other side, by **stating** it:

* **all 66 shipped exercises have an explicit classification.** `ProductionFocusClassificationTest`
  reads the real `WorkoutGenerator().getExerciseLibrary()` and measures coverage; the count is read
  from the catalogue and never hardcoded, so adding or removing an exercise moves the assertion with
  it.
* **the table names no exercise the catalogue does not have** — an entry for a retired exercise would
  be a classification of nothing.
* **an empty classification is invalid.** It is refused twice: at construction, by a `require` in the
  table's own `init`, and by `GenerationCandidate`, which will not admit a candidate with no focus.
  Neither is ever read as *"trains everything"*, and no entry is the whole vocabulary.
* **entries are held in the vocabulary's canonical order**, so the same id always classifies the same
  way and two runs cannot differ.
* **membership is data, not a rule.** There is no `when`, no `if`, no lookup by category, sub-category,
  family or training style. `ProgramGenerationFlowArchitectureTest` bans those identifiers *by name*
  from the file, and
  `theClassificationIsNotDerivedFromTheCataloguesOwnGroupings` proves the claim is falsifiable by
  reading the real catalogue: several exercises share one category and state **different** focus sets,
  which a `when (category)` classifier could not reproduce.

The table is a value holding no collaborator, constructing with nothing, and reachable through P23's
port — so a future user-facing *Goals & Focus* editor, or a persisted exercise→focus map, would
replace it as the same port's implementation rather than sit beside it. That is a **later owner
decision**, recorded, not resolved here.

---

## 3. The configuration is the draft's

`focus`, `schedule` and `duration` are read from the working draft and forwarded unchanged. There is
no second configuration object and no field that could fall back: a pass that substituted
`FocusPlan.DEFAULT` or a default cadence would plan a Program the user did not ask for while the draft
beside it stored the configuration they did.

`mode` is not read — generation *states* it. `ProgramGeneratedEditor` writes `GENERATED` and the focus
the plan was built for into the next draft, because mode and configuration are revision content (§6)
and a draft whose stored mode disagreed with the plan beside it would be saving a false fact.

---

## 4. Equipment is passed through unchanged

`availableEquipment` reaches the request **verbatim**. No normalisation, no widening, and **no
filtering of the catalogue before the request is built**: the whole catalogue goes to the planner, and
`GenerationRequest.isUsable` decides usability through
`availableEquipment.containsAll(candidate.requiredEquipment)`. An exercise the user cannot perform is
*reported* by the planner, not removed from the library view, so the fact survives into the result.

**An empty set means "the user declared no equipment"** — not "constrain nothing". This is P24's
documented reading of `SettingsManager.availableEquipmentFlow`'s empty default, and it is deliberately
*not* the legacy `isAccessibleWith` rule, which would hand a user who owns nothing a plan full of bar
and band exercises. `SettingsManager` is not a collaborator of the service: the value is read by
`MainViewModel` at the moment of the pass and handed to the controller as a port, so the UI layer holds
no equipment rule of its own and the service applies none either.

The consequence is stated rather than avoided: a user who has declared no equipment gets a plan built
from bodyweight exercises, and a focus whose every candidate needs equipment is **refused** with a
typed reason instead of being planned as nothing.

---

## 5. Generate and Regenerate alter the Draft only

```text
Generate / Regenerate  →  the next working draft
Save                   →  the only route to a Revision (§6, §7)
```

A generation pass holds **no repository, no DAO, no Room, no clock, no id generator of its own and no
zone** — the absence is the guarantee, not a promise. It mints no revision identity, creates no
Program, plans no slot and chooses no date; the composition root's own `IdGenerator` supplies draft
day and element handles through `DraftIdSource`, and `ProgramEditorService.save` remains the only
place a revision is minted and the only route to storage.

`Generate` and `Regenerate` are **the same reconciliation**, which is the generated domain's own
recorded decision: a `Regenerate` that discarded pinned content, or a `Generate` that kept content a
regenerate would have replaced, would make the meaning of a pin depend on which button the user
reached for. Both preserve pinned and user-authored content through `PlanReconciler`, and both return
the same `ReconciliationReport` so a screen can show what happened.

Three outcomes are typed, not a boolean (§28):

| result | meaning | notice |
| --- | --- | --- |
| `Generated` | the plan exists and is reconciled into the draft | `GENERATED` (Done) |
| `Refused(NoExerciseStatesItsFocus)` | the catalogue states no focus for any exercise | `GENERATION_UNAVAILABLE` (Refused) |
| `Refused(NothingPlannable)` | nothing in the library can serve this configuration | `GENERATION_REFUSED` (Refused) |
| `Failed(cause)` | the pass threw; the cause is surfaced | `GENERATION_FAILED` (Failed) |

A refusal and a failure both leave the user's draft exactly as it was. `GENERATION_UNAVAILABLE` is
*kept* rather than deleted: P24 closed the gap, but a gap that can reopen needs a sentence.

---

## 6. What P24 does **not** do

* **No adaptive integration.** `GenerationPreferences.NONE` is the neutral representation the request
  carries. `adaptivePreferredExerciseIds`, `recentExerciseIds`, `recentExposureByFocus`,
  `recentLoadByFocus` and `recovery` are left exactly as that states them; the Stage-1 adaptive engine
  and `PilotProgressionProfiles` are not read, and no hidden adaptive rule was added to compensate. A
  plan built from the configuration alone is the honest plan this stage can produce.
* **No Scheduler integration.** No slot is created, moved or cancelled; no date is chosen or read.
* **No persistence of a generated plan.** No Room migration, no new table, no generated-plan store.
  `Save` is unchanged.
* **No legacy removal.** `WorkoutGenerator` remains the app's catalogue owner, and P23's closed list
  of four readers of `getExerciseLibrary` is unchanged — the new service reads the catalogue *through*
  the boundary, which is what keeps that list closed.
* **No change to `domain/program/generated`.** Not one file was modified; the package is exactly the
  eight files PR 10 established, and it still reaches no application layer, no data model and no
  `android`/`androidx` type.
* **No large editor redesign.** The editor keeps its existing state path; the only UI change is that
  the Generate button now produces a plan, and a "Plan it again" button calls the same operation.

---

## 7. Architecture gaps recorded

| gap | where it is recorded | owner |
| --- | --- | --- |
| The classification is authored, not user-editable. A user cannot correct an entry in-app, and a taxonomy change is a code change. | `ProductionFocusClassification` KDoc; `withFocusSource` in `ProgramsRig` | a user-facing *Goals & Focus* editor, or a persisted exercise→focus map. Later stage. |
| `GenerationPreferences`' plain signals have no production source. | `ProgramGenerationService` KDoc, "Preferences and policy are this stage's stated neutrality" | the Adaptive integration stage (§30 step 12). |
| `availableEquipment` defaults to an empty set, which under generation's strict reading means *the user owns nothing*. | §4 above; the service KDoc | **decided in P24** and stated: strict reading retained. Reopening it is a product decision. |
| A plan produced with no adaptive integration cannot avoid repeating exercises across cycles beyond the planner's own diversity rule. | — | the Adaptive stage. |
| The legacy `WorkoutGenerator` remains the catalogue owner. | P23's closed reader-list gate | the legacy-removal stage. |

---

## 8. Claim → test

| claim | test |
| --- | --- |
| every shipped exercise states the focuses it trains | `ProductionFocusClassificationTest.everyShippedExerciseStatesTheFocusesItTrains` |
| the table names no exercise the catalogue lacks | `…theClassificationNamesNoExerciseTheCatalogueDoesNotHave` |
| no exercise is stated with an empty focus set | `…noShippedExerciseIsLeftWithoutAStatedFocus` |
| no exercise is stated as training the whole vocabulary | `…noExerciseIsStatedAsTrainingTheWholeVocabulary` |
| only the seven legal focuses appear | `…everyStatedFocusIsOneOfTheSevenTheVocabularyDefines` |
| classification is deterministic and canonically ordered | `…theClassificationIsDeterministicAndOrderedByTheVocabulary` |
| classification is not derived from the catalogue's groupings | `…theClassificationIsNotDerivedFromTheCataloguesOwnGroupings` |
| all seven focuses are reachable, including the three the catalogue never names | `…theClassificationCrossesTheCataloguesVocabulariesRatherThanFollowingOne` |
| an unknown id is unclassified, not given a focus | `…anUnknownIdIsStatedAsUnclassifiedRatherThanGivenAFocus` |
| the table is the port's own contract and is substitutable | `…theClassificationIsThePortsOwnContractAndIsSubstitutable` |
| the real catalogue reaches the planner as classified candidates, with nothing unclassified | `ProgramGenerationServiceTest.theRealShippedCatalogueReachesThePlannerAsClassifiedCandidates` |
| the real catalogue with no equipment plans only equipment-free work | `…theRealCatalogueWithNoEquipmentStillPlansFromTheExercisesThatNeedNone` |
| the draft's focus, schedule and duration reach the request unchanged | `…theDraftsOwnFocusScheduleAndDurationReachTheRequestUnchanged` |
| a custom focus share survives into the plan | `…aCustomFocusShareIsCarriedThroughRatherThanReplacedByAnEvenSpread` |
| equipment is forwarded and never normalised | `…availableEquipmentReachesTheRequestUnchangedAndIsNeverNormalised` |
| the whole catalogue reaches the planner; the constraint is the request's own | `…theWholeCatalogueReachesThePlannerAndTheConstraintIsTheRequestsOwn` |
| no adaptive signal is computed; the neutral representation reaches the request | `…noAdaptiveSignalIsComputedAndTheNeutralRepresentationReachesTheRequest` |
| the pass reaches `ProgramGeneratedEditor` and `PlanReconciler`; pinned content survives | `…thePassReachesProgramGeneratedEditorAndPlanReconcilerSoPinnedContentSurvives` |
| regenerate is the same reconciliation as generate | `…aRegenerationIsTheSameReconciliationAsAGenerate` |
| only the next immutable draft changes | `…thePassProducesTheNextImmutableDraftAndMutatesNothingItWasGiven` |
| an unclassified catalogue is refused, not generated around | `…aCatalogueThatStatesNothingIsRefusedRatherThanGeneratedAround` |
| an unservable configuration is refused, not planned as nothing | `…aConfigurationNothingCanServeIsRefusedRatherThanPlannedAsNothing` |
| a failure is surfaced, not absorbed into an empty draft | `…aFailureIsSurfacedRatherThanAbsorbedIntoAnEmptyDraft` |
| Generate produces a new working draft and persists nothing | `ProgramsControllerTest.theGeneratedEntryPathIsRealAndItsPlanIsProducedFromTheRealCatalogue` |
| a refused generation leaves the user's own content intact | `…aRefusedGenerationIsReportedAndLeavesTheUsersOwnDraftIntact` |
| an unclassified catalogue reports `GENERATION_UNAVAILABLE` and changes nothing | `…aCatalogueThatStatesNoFocusAtAllIsReportedAsUnavailableAndChangesNothing` |
| regenerate preserves pinned, user-authored content | `…aRegenerationGoesThroughTheSameReconciliationAndKeepsTheUsersContent` |
| the classification is data, not a rule over the catalogue's groupings | `ProgramGenerationFlowArchitectureTest.theClassificationIsAStatedTableAndNotARuleOverTheCataloguesGroupings` |
| no focus is ever substituted for a missing one | `…theClassificationNeverSubstitutesAFocusForAMissingOne` |
| the classification is read through the port, not constructed | `…theClassificationIsReadThroughTheFocusSourcePortAndNotConstructed` |
| the classification holds no collaborator and reads no state | `…theClassificationHoldsNoCollaboratorAndReadsNoState` |
| the service runs the existing components rather than restating them | `…theServiceRunsTheExistingDomainComponentsRatherThanRestatingThem` |
| the service holds no repository, storage or clock | `…theServiceHoldsNoRepositoryNoStorageAndNoClock` |
| the service writes no revision and creates no Program | `…theServiceWritesNoRevisionAndCreatesNoProgram` |
| the service consults no adaptive source | `…theServiceConsultsNoAdaptiveSource` |
| the neutral preferences are the declared defaults, and the pass's required inputs are required | `…theServiceStatesTheNeutralPreferencesRatherThanDefaultingThemQuietly` |
| the result is the three classes the UI must tell apart | `…theResultIsTheThreeClassesTheUiMustTellApart` |
| the controller reaches the service and nothing below it | `…theControllerReachesTheServiceAndNothingBelowIt` |
| the controller consults no repository and no Room | `…theControllerConsultsNoRepositoryAndNoRoom` |
| one construction site, in the composition root | `…theCompositionRootProvidesTheServiceAndNoOneElseBuildsOne` |
| the container is not a service locator for this node | `…theCompositionRootIsNotAServiceLocatorForThisNode` |
| the view model hands over and builds neither | `…theViewModelHandsTheServiceAndTheEquipmentOverAndBuildsNeither` |
| the pure package gained no file and reaches nothing new | `…thePureGeneratedPackageGainedNoFileAndReachesNothingNew` |
| the generated editor is reached and not reimplemented | `…theGeneratedEditorIsReachedAndNotReimplemented` |
| the service speaks in the domain's own draft values | `…theServiceSpeaksInTheDomainsOwnDraftValues` |
| the boundary has exactly one production consumer | `ProductionGenerationBoundaryArchitectureTest.theBoundaryIsWiredExactlyOnceThroughTheGenerationService` |
| the composition root wires the service, not the planner | `ProgramGeneratedArchitectureTest.theCompositionRootWiresGenerationOnlyThroughTheApplicationService` |
| the UI reaches generation only through the application service | `…theUiReachesGenerationOnlyThroughTheApplicationService` |

---

## 9. RED evidence

`scripts/program-stage24-red-mutations.sh` — eleven mutations, one per rule above, over
`ProductionFocusClassification.kt`, `ProgramGenerationService.kt`, `ProgramsController.kt` and
`AppContainer.kt`, each applied alone and re-checked for (a) real code surviving comment stripping,
(b) the source actually changing, and (c) the row being type-correct in the whole tree. The oracle is
the P24 behavioural suites, the P24 architecture gate, **and** the gates whose claims P24 inverts or
touches: P23's `ProductionGenerationBoundaryArchitectureTest` and PR 10's
`ProgramGeneratedArchitectureTest` (whose "unwired" pins P24 deliberately reversed), together with the
Program UI's own architecture suite.

| # | mutation | expected |
| --- | --- | --- |
| 1 | a missing classification is admitted as the whole `Focus` vocabulary | caught |
| 2 | a missing classification is silently dropped | caught |
| 3 | membership is inferred from `ExerciseCategory` | caught |
| 4 | membership is inferred from `ExerciseSubCategory` | caught |
| 5 | membership is inferred from the family / training-style map | caught |
| 6 | available equipment is widened the legacy way | caught |
| 7 | the service bypasses `ProgramGeneratedEditor` | caught |
| 8 | the service bypasses `PlanReconciler` | caught |
| 9 | a generation pass replaces pinned / user-authored content | caught |
| 10 | the controller short-circuits into `GENERATION_UNAVAILABLE` | caught |
| 11 | the service consults the Stage-1 adaptive source | caught |
| — | control: the unmutated tree stays GREEN | not caught (as required) |

The exact verdicts of the run that landed this stage are in the PR description; the script prints its
own table and restores every mutated source byte-identically (`md5sum -c` against a baseline captured
at the start of the run).
