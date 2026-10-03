# Program System — Generation Preview & Reconciliation (PR 26)

Scope: **§30 step 26.** The stage that makes generation *explainable and previewable* without changing
the planner. P24 already returned three values from one generation pass; this stage makes the two the
UI had been discarding **visible to the user**, and adds the one operation that lets a user accept a
plan they have read instead of one they have to trust.

Reference architecture: `docs/Monk Fitness — Program System Implementation Blueprint.MD` (cited below
as **§N**). Companions: `docs/PROGRAM_GENERATION_PRODUCTION_FLOW.md` (PR 24, the pass this stage
projects), `docs/PROGRAM_GOALS_FOCUS_AUTHORING.md` (PR 25, the configuration a pass is planned for),
`docs/PROGRAM_GENERATED_PLANNER.md` (PR 10, the pure planner, **unchanged**).

---

## 1. What P24 already produced, and what this stage does with it

`GeneratedDraftEdit` is not new — §30 step 10 built it and §30 step 24 wired it. It has carried three
values since then, and the controller used one of them:

```text
GeneratedDraftEdit
    draft            → used: assigned to workingDraft by Generate/Regenerate
    plan             → DISCARDED: the plan's days, focuses, elements and limitations
    reconciliation   → DISCARDED: what happened to the content that was already there
```

§7's editor supports `Generate`, `Preview`, `Regenerate`, `Pin` and user override, and §7's
*"conflicts with user choices are shown explicitly"* needs a screen. Both were unsatisfiable: the two
values that answer them existed and were thrown away, and the only way to show them would have been to
build a second plan.

**This stage changes no generation semantics.** It adds one operation, one presentation model, one
controller-held pending value and one screen section, and it surfaces two values that already existed.

---

## 2. Generate / Preview / Apply / Save — four distinct operations

```text
CURRENT WORKING DRAFT
        │
        ├── Preview ──→ PROSPECTIVE RESULT
        │                    │
        │                    ├── Apply ──→ CURRENT WORKING DRAFT
        │                    │
        │                    └── (close) ──→ nothing changed
        │
        └── Save ──→ PERSISTED PROGRAM / REVISION
```

| | what runs | what changes | what is stored |
| --- | --- | --- | --- |
| **Generate** | the whole pass | the working draft, immediately | nothing |
| **Regenerate** | the same pass (§7: the two are one reconciliation) | the working draft, immediately | nothing |
| **Preview** | the same pass | nothing — a prospective result is *held* | nothing |
| **Apply** | nothing | the working draft, from the held result | nothing |
| **Save** | §27's save | nothing | Program, Revision, plan, initial Slots |

`Preview ≠ Apply ≠ Save` is the whole point. A preview never becomes a hidden save, and it never
replaces the current draft without the user saying so.

### Preview uses the *same* pipeline, provably

`ProgramGenerationService.preview` is one delegating call:

```kotlin
fun preview(draft: ProgramEditorDraft, availableEquipment: Set<Equipment>): ProgramGenerationResult =
    edit(draft, availableEquipment)
```

There is no `previewPlan`, no branch in the request assembly and no fourth caller of the planner.
`ProgramGenerationPreviewServiceTest` asserts that from the compiled class (exactly three public
entry points, all reaching the same private pass) **and** behaviourally, by comparing a Preview's
plan, reconciliation and prospective draft against a Generate's over the same input.

A preview path that diverged would be silent: the numbers would differ and nothing would crash.

---

## 3. The pending preview is a temporary operation result

`ProgramsController.pendingPreview: GeneratedDraftEdit?` holds what the service returned — no more.
It is not a source of truth: there is no second `ProgramEditorDraft` in the UI state, no `FocusPlan`
of its own, no schedule, no generated configuration, and nothing in it is ever saved. Only the route
from the UI layer to storage remains `saveDraft`.

### Why invalidation rather than comparison

Any change to the working draft clears it immediately, from one place
(`clearGenerationPreview()`). The alternative — a hash, a timestamp or a revision pointer to the draft
a preview was built from — is a **second identity system invented for one screen**, and every way of
getting it wrong (a rebuild that mints a new identity, a rebase that quietly edits the preview, a
`RevisionConflict` with no owner) is worse than clearing. Clearing is also the only option that cannot
leave a user looking at a plan built for a draft that no longer exists.

The invalidating routes, all asserted mechanically:

| route | where |
| --- | --- |
| any draft edit (name, description, mode, duration, schedule, focus, days, elements, prescriptions, pin) | `editDraft` |
| open another editor flow | `beginEditorFlow` |
| discard the draft | `discardDraft` |
| Generate / Regenerate | `generateDraft` |
| Apply | `useGenerationPreview` |
| Save | `saveDraft` |

§30 step 26 forbids a `RevisionConflict` and a timestamp/hash identity. Neither exists here.

---

## 4. The presentation model, and what a screen may not do

`ui/programs/ProgramGenerationPreviewUi.kt` is a projection and nothing else. Every type in it is a
value of `Int` (a string resource or a count), `String` (an opaque exercise id) or a plain
enumeration-free value. `GenerationPreviewArchitectureTest` sweeps the declared **field types** and
fails on any generated-domain type — a UI state type that *holds* a generated value is as much a leak
as a screen that reads one, because it travels through the state.

Consequently a Composable may not:

* receive a `GeneratedPlan`, `GeneratedSlot`, `ReconciliationReport`, `GenerationLimitation`,
  `FocusAssignment`, `FocusUnusableReason` or `GeneratedDraftEdit` — it receives
  `ProgramGenerationPreviewUi` and nothing else;
* call `ProgramGenerationService` or name any generation owner;
* sort or rank anything (`days` and `elements` are the plan's own order, carried straight through);
* compute a count (every figure is the pass's own, read once by the controller);
* read a domain sentence.

The controller owns the whole application-result → UI mapping, in one function (`previewUiOf`), which
is why there is exactly one place in the codebase where a `GeneratedDraftEdit` becomes something a user
reads.

---

## 5. §14 — the limitation mapping

`GenerationLimitation.message` is developer-facing English built from the enum's own name
(`MOBILITY cannot be planned: none of the allowed exercises trains it`). §33 requires a limitation to be
*reported in a form the user can act on*, and §14 requires every word a user reads to come from a
resource. So the domain's message is **not** carried:

```text
FocusUnusableReason  →  focusUnusableReasonRes(reason)  →  R.string.programs_preview_reason_*
```

Each of the three reasons has its own sentence, because a focus the equipment forbids means something
different — and something *actionable* — from one nothing in the user's selection trains. The mapping is
exhaustive over the enum on purpose: a fourth reason without a sentence would be a limitation the
Preview shows as *nothing*, which is the defect this stage exists to remove.

The same treatment applies to focuses (`focusLabelRes`, the established mapping) and to exercises (the
existing catalogue display mapping; the raw id is a fallback used only when the catalogue has no label).
There is no `Text(text = limitation.message)` anywhere.

All 23 new keys are translated in **all seven** shipped locales (`values`, `bg`, `es`, `it`, `pt`,
`ru`, `uk`); `ProgramsLocalizationTest` checks existence, that Russian and Ukrainian differ from
English word for word, and that no added locale has a copied block.

---

## 6. Conflicts are facts, not errors

`ReconciliationReport.conflicts` is the main user-facing signal a Preview adds. A conflict is §7's
**preserved despite disagreement**: content the *user owns* — pinned, or authored by them — that the
plan would not have produced. It is kept, it is not an error, and the Preview never "fixes" it.

Two things the Preview deliberately does **not** do:

* **It never shows a user's own element as removed.** `DROPPED` is generated content that is no longer
  needed; the reconciler cannot drop a user's element, and a UI that listed one as removed would be
  telling the user their own work is gone.
* **It never pairs a dropped element with an added one.** `ReconciliationReport` reports a replacement
  as its two halves *precisely because* the planner states no relation between them (§7's own recorded
  decision). A presentation layer that paired them would assert something the domain refused to, so the
  Preview's shape carries no field that could hold such a relation — and the gate checks the field list.

`DAY_REMOVED` is counted and described separately from a removed exercise: a day disappearing is not a
count of exercises, and folding the two together would misreport what happened.

---

## 7. Generate and Regenerate are unchanged

Both still *immediately apply* the result — that is their existing meaning and this stage does not
change it. Regenerate is still `= generateDraft()`, i.e. the domain's own decision that the two are one
reconciliation. What they gained is one line: both clear a pending preview first, so an unchosen plan
is never left on screen beside a plan that was applied without asking.

---

## 8. Refusal and failure

§28's three classes are preserved, and Preview answers in the same three:

| | working draft | pending preview | notice |
| --- | --- | --- | --- |
| **Refused** | untouched | unchanged (a new refusal publishes nothing) | the refusal's own sentence |
| **Failed** | untouched | unchanged | `GENERATION_FAILED` (`Failed`, never a success) |

The absence of a plan is never turned into an **empty preview**, and a fake plan is never invented to
fill one — a user who reads an empty preview as *"this is what your plan would be"* has been told
something false, which is §33's whole subject.

---

## 9. Files

| file | layer | change |
| --- | --- | --- |
| `domain/usecase/ProgramGenerationService.kt` | application | **+1 method** — `preview` delegates to the existing private `edit` |
| `ui/programs/ProgramGenerationPreviewUi.kt` | UI/application | **new** — the presentation model, the resource vocabulary, the reason mapping |
| `ui/programs/ProgramsController.kt` | UI/application | `pendingPreview`, `previewDraft`, `useGenerationPreview`, `dismissGenerationPreview`, `previewUiOf`, `clearGenerationPreview`, and the invalidation calls |
| `ui/programs/ProgramUiModels.kt` | UI/application | `+1 field` — `ProgramsUiState.generationPreview` |
| `ui/programs/ProgramNotice.kt` | UI/application | `+1 notice` — `GENERATION_PREVIEWED`, which says the draft has *not* changed |
| `ui/screens/ProgramEditorScreen.kt` | Compose | the **Preview** button and `GenerationPreviewSection` |
| `res/values*/strings.xml` (×7) | resources | 23 keys, translated |
| `domain/program/generated/**` | pure domain | **unchanged** — still exactly P10's eight files |

No file in `domain/program/generated` was modified. The stage added no file to the pure package and no
table, and `ProgramGeneratedArchitectureTest`'s existing census plus
`GenerationPreviewArchitectureTest.theGeneratedDomainIsUntouchedByThisStage` both hold.

---

## 10. Architecture gaps recorded

* **The pending preview has no persistence of its own, by design.** If a user leaves the editor and
  comes back, the preview is gone and only the working draft survives — the same guarantee
  `workingDraft` already gives across a configuration change, and the same recorded gap across a
  process death (see `docs/PROGRAM_UI_NAVIGATION.md`). Restoring a preview across process death would
  need a restore seam that does not exist; building one for a value the user can regenerate in one tap
  would be a poor trade, and it is recorded rather than silently decided.
* **The exercise labels come from `exerciseOptions`, which the editor session loads once.** A preview
  therefore falls back to the raw exercise id for an exercise the catalogue picker does not offer. That
  is the same fallback the draft's own element rows use (`nameRes = 0` → the id), so it is consistent
  rather than new — but it is a display-quality gap, not a correctness one, and it is the composition
  root's catalogue read that would close it.
* **`ProgramGenerationPreviewUi.changes` is carried but the screen does not yet render it as an
  expandable list.** The counts are shown, and the conflicts and limitations are listed; the per-change
  detail is available on the state for the caller that wants it. §6's *"compact summary plus an
  expandable list of details"* is met by the summary and by [conflicts]; the full expansion is a
  rendering decision left to the next UI pass rather than a missing fact.

---

## 11. Verification

Measured on `feat/program-stage26-generation-preview`, base `081305f` (PR #327):

| | |
| --- | --- |
| full JVM (fresh) | **301 classes / 2953 tests / 0 failures / 0 errors / 0 skipped** |
| baseline before this branch | 298 / 2887 / 0 / 0 / 0 |
| compile gates | `compileDebugKotlin`, `compileDebugUnitTestKotlin`, `compileReleaseKotlin`, `compileReleaseJavaWithJavac`, `assembleDebug` — all green |
| RED mutations | control GREEN · caught **14** · missed **0** · not-a-catch **0** · byte-identical restoration |

Reproduced by the commands below.

| check | command |
| --- | --- |
| full JVM suite + census | `./scripts/test-census.sh` |
| the P26 suites | `./gradlew :app:testDebugUnitTest --tests '…ProgramsGenerationPreviewTest' --tests '…GenerationPreviewArchitectureTest' --tests '…ProgramGenerationPreviewServiceTest'` |
| compile gates | `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileReleaseKotlin :app:compileReleaseJavaWithJavac :app:assembleDebug` |
| RED mutations | `scripts/program-stage26-red-mutations.sh` — **control GREEN, caught 14, missed 0, not-a-catch 0, every mutated source restored byte-identically** |

### Claim → test

| claim | test |
| --- | --- |
| a Preview changes no draft, writes no row, mints no Revision, plans no Slot | `ProgramsGenerationPreviewTest.aPreviewDoesNotChangeTheWorkingDraft`, `…CreatesNoRevisionAndWritesNoRowOfAnyKind`, `…DoesNotPlanASlotOrTouchTheExistingStoredContent` |
| a Preview is the same pass as a Generate | `ProgramGenerationPreviewServiceTest.aPreviewIsTheGeneratePassOverTheSameInput`, `previewIsOneCallIntoTheSamePrivatePassRatherThanAPipelineOfItsOwn` |
| the Preview exposes the plan, in order, with focuses and prescriptions | `…thePreviewExposesThePlanItWouldApply`, `…everyPreviewedDayNamesItsPrimaryAndSecondaryFocusesAsResources`, `…everyPreviewedExerciseNamesItsCatalogueEntryAndItsPrescription` |
| limitations are surfaced and mapped to resources | `…aPreviewNamesEveryLimitationThePlannerReported`, `…aLimitationIsMappedToAResourceAndNeverToTheDomainsOwnSentence`, `GenerationPreviewArchitectureTest.everyLimitationReasonIsMappedToItsOwnResource` |
| reconciliation counts, conflicts and removed days are reported | `…thePreviewReportsTheCountsOfWhatWouldHappenToTheDraft`, `…thePreviewReportsAConflictAsSomethingThatIsKept`, `…aUsersElementIsNeverReportedAsRemoved`, `…aRemovedDayIsReportedSeparatelyFromARemovedExercise` |
| no invented dropped→added pairing | `…thePreviewNeverPairsARemovedExerciseWithAnAddedOne` |
| Apply installs the draft, clears the preview and the review, persists nothing | `…applyPreviewInstallsTheProspectiveDraftAndNothingIsPersisted`, `…applyPreviewInstallsExactlyWhatWasPreviewed`, `…applyPreviewClearsAStaleReview` |
| Save after Apply persists normally | `…saveAfterApplyPersistsTheAppliedDraftNormally` |
| every draft change invalidates a pending preview | `…editingTheNameClearsThePreview`, `…changingTheFocusClearsThePreview`, `…changingTheScheduleOrTheDurationClearsThePreview`, `…pinningOrUnpinningClearsThePreview`, `…addingOrRemovingExercisesOrDaysClearsThePreview`, `…openingAnotherEditorFlowClearsThePreview`, `…discardingTheDraftClearsThePreview` |
| Generate/Regenerate stay immediate and clear the preview | `…generateClearsAPendingPreviewAndAppliesImmediately`, `…regenerateClearsAPendingPreviewAndAppliesImmediately`, `GenerationPreviewArchitectureTest.generateAndRegenerateStayImmediateApplicationsAndClearThePreview` |
| refusal and failure leave the draft untouched | `…aPreviewRefusalLeavesTheDraftUntouchedAndPublishesNoPreview`, `…aPreviewFailureLeavesTheDraftUntouchedAndWritesNothing`, `…generateFailureLeavesTheDraftUntouched` |
| P25's configuration preservation holds through a Preview | `…aFocusedConfigurationIsWhatThePreviewPlansFor`, `…aCustomAllocationReachesThePreviewWithItsExactPercentages` |
| a Preview's notice is distinguishable from Generate's | `…thePreviewNoticeSaysTheDraftHasNotChanged`, `…generateStillPublishesItsOwnNoticeAndPreviewItsOwn` |
| each limitation reason gets its own sentence | `…eachLimitationReasonGetsItsOwnSentenceAndNotOneGenericFallback` |
| the UI reaches no generated domain and no storage | `GenerationPreviewArchitectureTest` (17 tests) |

### Two rows were MISSED on the first run, and both were oracle gaps

Recorded because a mutation that does not fire is worse than no mutation: it reads as a hole in the
oracle and sends the next session hunting for one that is not there.

1. **The limitation-reason mapping could collapse to one generic sentence and nothing caught it.** Every
   limitation the *shipped* catalogue produces has the same reason, so a Preview that mapped all three
   to one resource was indistinguishable from a correct one. `eachLimitationReasonGetsItsOwnSentenceAndNotOneGenericFallback`
   now states a **fixture** catalogue that produces two different limitations in one pass — one focus
   nothing trains, one focus the equipment forbids — and measures them apart.
2. **Nothing asserted which notice a Preview publishes.** `thePreviewNoticeSaysTheDraftHasNotChanged`
   and `generateStillPublishesItsOwnNoticeAndPreviewItsOwn` now do, including that the two notices are
   different resources rather than one sentence under two names.

Four further rows initially failed to compile (`saver.save` is `suspend`; an `emptyList().map {}` and a
substituted field type both broke inference), so no oracle ran and they were charged `NOT A CATCH`.
Each was rewritten as a *plausible* implementation — a narrowed invalidation, a `filter` that forgets
pins, a "we got days so there is nothing to warn about" guard, an added field with a default — so that a
real oracle executes.