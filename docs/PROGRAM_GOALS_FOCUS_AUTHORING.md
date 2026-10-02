# PROGRAM_GOALS_FOCUS_AUTHORING — §7's Goals & Focus, editable

*P25 — the branch `feat/program-stage25-goals-focus`, off `main` at P24/#326 (`ffba113`).*

---

## 1. What this stage is

§7 lists **Goals & Focus** as a step of the Program Editor. The value behind it — `FocusPlan`, with its
three forms and seven focuses — has existed since §8 wrote it, `ProgramEditorDraft.focus` has carried it,
`ProgramsController.setDraftFocus` has accepted it, and P24's generation pass has read it as its planning
input. What did not exist was **a user**: the section was unreachable, so the one setting that says what a
generation is *for* could not be stated by the person who wanted it.

This stage adds the authoring surface and nothing else. The rule it exists to keep is a single chain:

```text
USER STATES GOAL + FOCUS
        ↓
FocusPlan
        ↓
ProgramEditorDraft.focus
        ↓
Generate / Save
```

No second state, no default the user did not choose, no percentage computed on their behalf, and no new
form, focus, mode, ranking or persistence mechanism.

## 2. Scope

```text
PRODUCTION
  ui/programs/GoalsFocusAuthoring.kt     NEW      the three pure rules the screen cannot own
  ui/programs/ProgramUiModels.kt         +1 field  ProgramDraftUi.focus, the draft's own value
  ui/programs/ProgramsController.kt      +1 line   focus = draft.focus, in presentationOf
  ui/screens/ProgramEditorScreen.kt      +section  Goals & Focus, the FOCUSED chooser, the CUSTOM dialog
  res/values*/strings.xml                +23 keys × 7 locales
```

`setDraftFocus` and `ProgramDraftEditor.withFocus` were **already** exactly what the brief asks the
controller to be, so neither changed — only the presentation that carries the value to the screen was added.

## 3. The three forms, and what each may decide

| Form | What the user states | What the screen may not do |
| --- | --- | --- |
| `BALANCED` | nothing — `FocusPlan.Balanced`, no percentages, no focus selection | offer a focus or a share "as a starting point" |
| `FOCUSED` | one or more of the seven focuses | name one for them, or keep the tap order as a rank |
| `CUSTOM` | a whole percent per focus, summing to 100% | pre-fill a split, complete a sum, or round |

Both choosable goals are settled by a **dialog the user confirms**, because each needs something only the
user can state. Tapping a goal chip *asks*; the draft changes on the confirmation. So switching to FOCUSED
names no focus until the user names one, and switching to CUSTOM opens an editor whose fields are all
empty.

The share dialog starts at `sharesFrom(focus)`: the draft's own CUSTOM shares when it already is one, and
**every focus unallocated** otherwise. `0` is not a share the domain accepts — it is the *absence* of one,
and no even split, no `100%` for the first focus and no `20/20/…` is ever written.

## 4. Owner decisions

**The CUSTOM dialog's working state is a value, not a `FocusPlan`.** `FocusPercentEntry` is a plain
`data class` in `ui/programs` — plain Kotlin, no Compose, no Android, no storage. An allocation that does
not sum to 100% cannot be a `FocusPlan` at all, so the temporary state deliberately is not one: it holds
the user's typed numbers, and `toFocusPlan()` is the only way out of it, going through `FocusPlan.custom`.
It is never written to the draft, never persisted, and never read back as the draft's configuration.

**`Done` is disabled, not clamped.** The dialog offers confirmation only once `FocusPlan.custom` accepts
the numbers. A refused sum leaves the draft untouched rather than being redistributed over the focuses the
user named.

**The last FOCUSED focus cannot be unchecked**, in two independent places: the chip is disabled when it is
the only one named, and `toggledFocus` returns `null` for that toggle rather than building an empty
`Focused`. Both are the domain's rule (`FocusPlan.Focused` refuses an empty list) surfaced rather than
re-implemented.

**The screen holds no configuration.** `GoalsAndFocusSection` takes the draft's `FocusPlan` and calls
`controller.setDraftFocus` — once, from one place. The architecture gate pins that there is exactly one
`setDraftFocus(` call site in the screen and no `var`/`mutableStateOf` holding a `FocusPlan`.

**A CUSTOM share is displayed with its own string**, `programs_editor_focus_share` (`%1$d%%`), rather
than reusing the dialog's "still to assign" line — the two sentences say different things and conflating
them would read as a remaining total next to each share.

**FOCUSED's description names the absence of a ranking** ("they are trained together, and none of them
ranks above another"), because §8 states no priority and a multi-select that looks ordered is exactly the
mistake the canonical order exists to prevent.

## 5. Generation integration

P24 is unchanged. The chain the brief asks to be proven:

```text
UI/controller draft.focus  →  ProgramGenerationService  →  GenerationRequest.focus  →  GeneratedPlanner
```

is measured in `ProgramsGoalsFocusTest` through the **controller** on the real catalogue: a
`Focused(PUSH, PULL)` draft generates a plan every element of which can serve PUSH or PULL, and a
`Custom(PUSH=70, MOBILITY=30)` draft comes back as that exact allocation — never `Focused`, never
`Balanced`, never another percentage set.

One assertion was written wrong and corrected: "the union of the planned exercises' focuses equals the
user's selection" is **false**, because an exercise states every focus it trains and a mobility drill
selected for `MOBILITY` also states `CORE` and `POSTURE`. The claim that is actually true, and that a
silently-replaced configuration would break, is *"every planned exercise can serve at least one focus the
user chose"* — which is what the rig's `everyPlannedExerciseServes` asserts.

## 6. Revision semantics

Focus is already part of `ProgramStructure` (§6 lists `goals/focus` among the structural aspects), so no
revision or persistence mechanism was touched. What P25 pins:

| claim | test |
| --- | --- |
| a focus-only change creates a Revision, naming that aspect | `…changingOnlyTheFocusCreatesARevision` |
| a no-op focus change creates none | `…anUnchangedFocusCreatesNoRevision` |
| the stored Revision is untouched until Save | `…theStoredRevisionIsUntouchedUntilTheUserSaves` |
| Save persists the chosen `FocusPlan`; reload returns it | `…focusedRoundTrips…`, `…aCustomAllocationRoundTrips…` |
| Generate creates no Revision and writes no row | `…generateNeverPersistsTheConfigurationAndLeavesStorageAlone` |
| after Generate the new draft keeps the choice | same test |

## 7. Architecture gate

`GoalsFocusArchitectureTest`, asserting over comment-stripped sources and the compiled shape:

```text
exactly three UI files may name FocusPlan, and no Composable may hold one
ProgramDraftUi.focus carries the draft's own value; the controller publishes draft.focus
the screen builds no allocation, sums no percentage and substitutes no DEFAULT
exactly one setDraftFocus( call site in the screen
the finished configuration goes through FocusPlan.custom / FocusPlan.focused
switching the goal never names a focus for the user; the seven are offered in vocabulary order
the screen reaches no generated domain, no DAO, no repository, no scheduler, no adaptive source
the authoring rules reach no framework, no service, no storage
23 entities / 20 DAOs — unchanged; §6 already stores the configuration on the Revision
```

Two gate checks are deliberately shaped rather than keyword-shaped, because a keyword check passes for the
wrong reason: the "no hidden default" rule rejects
`FocusPlan.focused(listOf(Focus.X))` *anywhere in the screen* (a single focus named at the moment the
goal is switched), and the "no ranking" rule rejects `sortedBy`/`sortedWith`/`.sort(` in the screen file.

## 8. Boundaries observed

Not done, by the brief's scope boundary: adaptive integration, exposure/load/recovery, scheduler or target
scheduling changes, session/runtime changes, removal of the legacy `WorkoutGenerator`, any new persistence
model or revision mechanism, import/export format changes, a redesign of the editor, automatic focus
recommendations, ranking between focuses, and any physiological coefficient or new percentage semantics.

No new goal, no new mode, no new focus, no second configuration model.

## 9. Verification

Numbers in the PR description; the shape is:

```text
full JVM           scripts/test-census.sh, gated on the gradle exit code, XML parsed
focused P25        GoalsFocusAuthoringTest, ProgramsGoalsFocusTest, GoalsFocusArchitectureTest
compile gates      compileDebugKotlin, compileDebugUnitTestKotlin, compileReleaseKotlin,
                   compileReleaseJavaWithJavac, assembleDebug
RED                scripts/program-stage25-red-mutations.sh
```

`:app:lintVitalRelease` fails pre-existing (`res/values/themes.xml` ResourceCycle) and is not stage
evidence.

## 10. Claim → test

| claim | test |
| --- | --- |
| the screen renders the draft's own configuration | `GoalsFocusArchitectureTest.theDraftsConfigurationIsTheOnlyOneTheUiHolds`, `…IsCarriedOnTheDraftAndOnItsPresentationAndNowhereElse` |
| the controller changes focus through the editor | `ProgramsGoalsFocusTest.aFocusChangeGoesThroughTheDraftEditorAndIsVisibleOnTheNextGeneration` |
| an edit clears the review | `…changingTheFocusClearsTheReviewThatDescribedThePreviousDraft` |
| BALANCED round trip | `…balancedRoundTripsThroughSaveAndReload` |
| FOCUSED single focus | `GoalsFocusAuthoringTest.choosingOneFocusIsAFocusedPlanNamingExactlyThatFocus` |
| FOCUSED multi-focus | `…choosingSeveralFocusesNamesAllOfThem` |
| canonical ordering, no tap-order priority | `…theOrderFocusesWereTappedInIsNotAPriorityThatSurvives`, `GoalsFocusArchitectureTest.theSevenFocusesAreOfferedAndNothingIsRanked` |
| the last focus cannot be removed | `…theLastFocusCannotBeRemovedSoAFocusedPlanNeverNamesNothing` |
| CUSTOM valid allocation | `…theUsersOwnSharesAreTheAllocation` |
| CUSTOM invalid total rejected | `…aShareThatDoesNotAddUpToOneHundredIsRefusedByTheDomainRatherThanCompleted` |
| zero / duplicate / unrepresentable states | `…aZeroMeansTheFocusIsNotInThePlanAndNeverBecomesAnAllocation`, `…theDomainRefusesAConfigurationTheUserCouldNotHaveTypedAndWeDoNotRepair`, `…oneFocusCarryingTwoSharesIsRefused` |
| focus-only change creates a Revision | `…changingOnlyTheFocusCreatesARevision` |
| focus no-op creates none | `…anUnchangedFocusCreatesNoRevision` |
| the stored Revision is untouched until Save | `…theStoredRevisionIsUntouchedUntilTheUserSaves` |
| Save persists, reload returns the same plan | `…aCustomAllocationRoundTripsThroughSaveAndReload` |
| the user's focus reaches generation unchanged | `…theUsersConfigurationIsWhatTheGenerationPassPlansFor`, `…aCustomAllocationReachesTheGenerationPassWithItsOwnPercentages` |
| Generate never persists the focus | `…generateNeverPersistsTheConfigurationAndLeavesStorageAlone` |
| no second source of truth | `GoalsFocusArchitectureTest.theDraftsConfigurationIsTheOnlyOneTheUiHolds` |
| no business calculation in the screen | `…theScreenComputesNoSharesAndDecidesNoConfiguration`, `…theScreensOnlyConfigurationWritesAreTheOnesThatHandAValueToTheController` |
| no hidden default | `…noFocusIsChosenForTheUserWhenTheGoalIsSwitched` |
| UI → generated domain is refused | `…theScreenReachesNoGeneratedDomainDirectly` |
| UI → DAO/repository/scheduler/adaptive is refused | `…theScreenReachesNoStorageNoSchedulerAndNoAdaptive` |
| no new persistence entity or DAO | `…theFocusConfigurationAddedNoEntityAndNoDao` |
| P24's generation behaviour stays green | `ProgramGenerationServiceTest`, `ProgramGenerationFlowArchitectureTest`, `ProgramsControllerTest` (unchanged, full suite) |