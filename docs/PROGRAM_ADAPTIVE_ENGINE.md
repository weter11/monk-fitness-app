# Program System — the Adaptive Engine, its Policy and the Aggregate Load Guard (PR 11)

Scope: **§30 step 11**, *Adaptive engine*. The Program System's own adaptive **domain decision layer**:
the explicit facts of a window, the signals derived from them, the policy that decides, the family's own
hierarchy that resolves a decision into a concrete change, and §18's aggregate load guard that may refuse
it — all as pure domain components with no collaborator, no clock and no persistence.

Reference architecture: `docs/Monk Fitness — Program System Implementation Blueprint.MD` (cited below as
**§N**). Companions: `docs/PROGRAM_DOMAIN_FOUNDATION.md` (PR 1), `docs/PROGRAM_ROOM_SCHEMA.md` (PR 2),
`docs/PROGRAM_SCHEDULE_FREQUENCY_CORRECTION.md` (PR 2.1), `docs/PROGRAM_DATA_ACCESS.md` (PR 3),
`docs/PROGRAM_COMPOSITION_ROOT.md` (PR 4), `docs/PROGRAM_LIFECYCLE.md` (PR 5),
`docs/PROGRAM_MANUAL_EDITOR.md` (PR 6), `docs/PROGRAM_SCHEDULER.md` (PR 7),
`docs/PROGRAM_SESSION_RUNTIME.md` (PR 8), `docs/PROGRAM_PROGRESS_HISTORY.md` (PR 9),
`docs/PROGRAM_GENERATED_PLANNER.md` (PR 10).

This stage **decides**, and nothing else. It does not wire itself into the session runtime, the scheduler,
a ViewModel, the UI or a repository; it does not persist a decision; it does not touch a revision, a slot
or a session. §30 step 12 owns all of that, and §9 of this document is the list of what is deliberately
left to it.

---

## 1. The graph

```text
AdaptiveInputSnapshot (PR 1)                       ─┐
familyOfExercise, the family's own window facts     │  ProgramAdaptiveSignalCalculator
the element the plan presents, and whose it is      ├─→ ProgramAdaptiveSignals      §13
                                                   ─┘
        ↓  ProgramAdaptivePolicy.evaluate           §15       state + action + reason
        ↓  ProgramProgressionRelation.resolve       §15/§16   before → after of one element
        ↓  ProgramAggregateLoadGuard.guard          §18       approved, or refused
ProgramAdaptiveResult                               §16/§22   AdaptiveDecision + AdaptiveAdjustment?
```

```text
SessionRuntime        ← untouched: the completion transaction is not this stage's
ProgramAdaptiveRepository (PR 3)   ← untouched: nothing is persisted here
AppContainer          ← untouched: nothing consumes the engine yet, so nothing is wired (§26)
```

| file | layer | responsibility |
| --- | --- | --- |
| `domain/adaptive/engine/ProgramAdaptiveElement.kt` | pure domain | the element a decision is about — the domain's own `EffectiveExercise`, its family, and **whose** it is (`AUTOMATIC` / `USER_AUTHORED` / `PINNED`) |
| `domain/adaptive/engine/ProgramProgressionRelation.kt` | pure domain | a family's declared ladder: which variants exist at which positions, and therefore exactly which moves are adaptations (§15) |
| `domain/adaptive/engine/ProgramAdaptiveReason.kt` | pure domain | the reason vocabulary — one stable token per rule, so a `HOLD` can say *which* hold it is |
| `domain/adaptive/engine/ProgramAdaptiveSignals.kt` | pure domain | §13's derived signals and the calculator that derives them from the window |
| `domain/adaptive/engine/ProgramLoadComparison.kt` | pure domain | two load profiles compared **channel by channel**, per scope, with the reason a channel cannot be compared (§17, §18) |
| `domain/adaptive/engine/ProgramAggregateLoadGuard.kt` | pure domain | §18's guard: baseline + adaptive delta + recent context, at one scope, with the policy's tolerances |
| `domain/adaptive/engine/ProgramAdaptivePolicy.kt` | pure domain | the window facts, the policy input, the decision, and **every threshold and confirmation count in one home** (§15) |
| `domain/adaptive/engine/ProgramAdaptiveEngine.kt` | pure domain | the orchestration: signals → policy → the family's own hierarchy → the guard → the decision and the adjustment |

## 2. Target versus legacy — the two adaptive generations

The tree already contains a **shipped Stage-1 adaptive engine**: `domain/adaptive/AdaptivePolicy.kt`,
`AdaptiveProgramEngine.kt`, `AdaptiveSignalCalculator.kt`, `AdaptiveDecision.kt`, `ProgressionResolver.kt`,
`PilotProgressionProfiles.kt`, `domain/usecase/AdaptiveWorkoutIntegration.kt` and
`data/repository/AdaptiveSessionDecisionRecorder.kt`, over the legacy `family_progression_state` and
`adaptive_decision_record` tables. §30 step 15 retires that generation. **This stage neither retires nor
retrofits it.**

The separation is mechanical, not documentary:

* the target engine lives in its own package (`domain/adaptive/engine`), so no declaration of its collides
  with a legacy one — the pilot's `AdaptiveAction`, `AdaptiveDecision`, `AdaptiveEvidence`, `AdaptiveSignals`
  and `AdaptiveState` are all still exactly what they were;
* `ProgramAdaptiveArchitectureTest.theEngineNamesNoneOfTheStageOneAdaptiveImplementation` scans every engine
  source (comments stripped, whole-token matches) for the legacy policy, engine, signal calculator,
  resolver, pilot ladders, repository and use cases. None of them is reachable;
* the inverse is asserted too — `…theStageOneAdaptiveGenerationIsStillThereUntouchedAndStillItsOwn` pins
  that every legacy file is still present, that the pilot vocabulary is unchanged
  (`MAINTAIN_STIMULUS`/`INCREASE_STIMULUS`/`REDUCE_STIMULUS`/`RECOVERY_LOAD`, four states), that the legacy
  test classes are still on disk, and that **no legacy file names anything this stage adds**;
* the whole suite runs, not just the new classes: the Stage-1 suites are green on this tree.

**One vocabulary is shared, and it is shared on purpose.** `AdaptiveState`
(`HOLD`/`PROGRESS`/`REGRESS`/`RECOVERY`) is what §23's `program_family_progression_state.adaptationState`
column stores, and PR 3 decided that. The target engine therefore returns that state — a *rename* of a
stored vocabulary is §30 step 15's business, not a decision this stage may take while both generations
exist. Everything else about the legacy generation stays out.

## 3. Inputs and outputs

```text
ProgramAdaptiveRequest
    decisionId, adjustmentId, decidedAt      identity and the moment — all supplied, none read
    snapshot: AdaptiveInputSnapshot          §11's frozen window (PR 1)
    element: ProgramAdaptiveElement          what is presented, its family, and whose it is
    relation: ProgramProgressionRelation     the family's own ladder
    window: ProgramAdaptiveWindow            the family's maintained facts (level, state, counts, cooldown)
    familyOfExercise: Map<String, String>    the caller's exercise→family classification
    availableExerciseIds: Set<String>        the user's own selection (§9)
    supersedesAdjustmentId: AdjustmentId?    the adjustment this one replaces, by reference (§16)
    policy: ProgramAdaptivePolicy            every threshold, in one place

ProgramAdaptiveResult
    decision: AdaptiveDecision               target, action, outcome, grounds (§15, §18, §22)
    adjustment: AdaptiveAdjustment?          present exactly when the decision was applied (§16)
    state: AdaptiveState                     the family's state after this window
    reason: ProgramAdaptiveReason            the single rule that produced it
    requestedAction: AdaptiveAction          what the policy asked for, before the rest was consulted
    signals: ProgramAdaptiveSignals          what was measured
    guard: ProgramGuardVerdict               what §18 said
    verdict: ProgramWindowVerdict            what the policy found in this window (§15)
```

`verdict` was **added by §30 step 12**, and it is recorded here because this document's field list is a
contract: the caller maintains the family's confirmation counts, its cooldown position and its recovery
exit count (`ProgramAdaptiveWindow`) from the policy's per-window answers, and a window cannot count
itself. The policy computes it inside `evaluate`, so the engine reports the finding rather than the caller
re-deriving it from the reason — `PROGRESSION_COOLDOWN` deliberately does not say *which* direction it is
holding. `docs/PROGRAM_ADAPTIVE_INTEGRATION.md` §14 records the change.

`requestedAction` is separate from `decision.action` for one reason: when the guard refuses a change the
decision is a `HOLD` — the change did not happen — and the record still shows that a `PROGRESS` was asked
for and refused. Flattening the two would make *"the program wanted to progress this family and the load
guard said not yet"* indistinguishable from an ordinary hold.

## 4. The policy (§15)

Every threshold, window and confirmation count lives in `ProgramAdaptivePolicy`, is stated as an integer
count or an integer ratio, and is read by the policy, by the signal layer's two bucketings and by the
guard — never re-derived at a call site. The v1 values (pinned by
`ProgramAdaptivePolicyTest.everyThresholdIsTheDocumentedV1Value`, and by
`ProgramAdaptiveArchitectureTest.thePolicyIsImmutableAndItsFieldsAreExactlyTheThresholdsItDocuments`):

| field | v1 | what it decides |
| --- | --- | --- |
| `trendMinimumExposures` | 3 | below this, no trend is measured at all |
| `progressMinimumExposures` | 4 | comparable exposures a progression window needs |
| `regressMinimumExposures` | 3 | comparable exposures a regression window needs |
| `regressMinimumShortfallSets` | 3 | sets the **recent end** must have fallen short by |
| `consistencyHigh*` | 3/4 | attendance at or above this fraction is high |
| `consistencyMedium*` | 1/2 | … and at or above this is medium; below it is low |
| `progressConfirmingWindows` | 2 | consecutive windows a progression must hold for |
| `regressConfirmingWindows` | 2 | the same, for a regression |
| `recoveryEntryConfirmingWindows` | 1 | windows a recovery pattern must hold for |
| `recoveryProlongedWindows` | 2 | windows the prolonged reduced-exposure pattern needs on its own |
| `recoveryExitQualifyingWindows` | 2 | windows completed in recovery before it exits |
| `progressionCooldownWindows` | 2 | windows a level change serves before the next one may follow |
| `variantRealignmentEnabled` | true | whether a same-level realignment may be proposed |
| `guardAllowedSetIncrease*` | 0/1 | sets an automatic change may add |
| `guardAllowedAmountIncrease*` | 0/1 | repetitions (or seconds) it may add |
| `guardAllowedWorkIncrease*` | 0/1 | working seconds it may add |
| `guardAllowedLevelSteps` | 1 | declared levels one decision may travel |
| `guardAllowedRestDecrease*` | 1/1 | rest may not shorten below this fraction |

The zeros are deliberate: **v1 allows no automatic volume increase**. The adaptive stage's job is to move
the family to the variant its own ladder declares next, and how much the user does is the plan's and the
user's business. A caller that wants a bounded increase states its own numerator — and the arithmetic is
exact, so `1/4` means *"37 repetitions of a 30-repetition baseline is inside +25%, 38 is not"*, which
`ProgramAggregateLoadGuardTest.theToleranceIsTheExactIntegerBoundaryThePolicyStates` measures.

### The state machine, in priority order

```text
1. recovery entry   recent load above the plan + (deterioration or reduced exposure), or the prolonged
                    reduced-exposure pattern          → RECOVERY   (never cooldown-gated)
2. recovery gating  a family in RECOVERY stays until recoveryExitQualifyingWindows is met, and leaves
                    to HOLD                            → RECOVERY_HELD / RECOVERY_EXITED
3. rest             an unsupported adaptation          → REST_CHANGE_UNSUPPORTED (a HOLD)
4. confirmed PROGRESS  §7's conditions + confirmation + cooldown elapsed → SUSTAINED_POSITIVE
5. confirmed REGRESS   the same, downward                              → SUSTAINED_NEGATIVE
6. cooling down     a direction that was earned and is held by the cooldown → PROGRESSION_COOLDOWN
7. awaiting         a direction that holds in this window and not yet in the previous one(s)
7b. the gates       the direction is there and one gate is not: LOW_CONFIDENCE, RECENT_LOAD_ELEVATED,
                    CAUTIOUS_CONTEXT, INCONSISTENT_ATTENDANCE
8. the default      INSUFFICIENT_EVIDENCE, STABLE_PLATEAU, or MIXED_EVIDENCE
```

**PROGRESS** (§7) requires all of: comparable exposures ≥ `progressMinimumExposures`; a **measured
positive trend**; a **recent end** that fell short of nothing; `EvidenceLevel.STRONG`; confidence above
`LOW`; `RecoveryContext.FAVORABLE`; attendance not `LOW`; a recent context not above the plan's own
prescription; confirmation across `progressConfirmingWindows`; and a cooldown that has elapsed (or a
family that has never changed level, where there is nothing to cool down from).

**HOLD** is the default and never a failure: insufficient history, a measured plateau, a window that
points two ways, a direction awaiting confirmation, a cooldown, a ceiling, a floor, an unavailable step,
an unsupported rest change, a user-owned element and a guard refusal each have their own reason.

**REGRESS** (§7) requires comparable exposures ≥ `regressMinimumExposures`, a **measured negative trend**,
at least `regressMinimumShortfallSets` sets of shortfall on the **recent end**, confirmation across
`regressConfirmingWindows`, and the cooldown. One bad result is not enough, by arithmetic rather than by
promise: the trend needs three exposures to exist at all, the shortfall is counted over the recent half,
and the conditions must hold in two consecutive windows.

**RECOVERY** (§14) is a *state*, not a medical assessment and not a change: it outranks a progression, is
never blocked by the progression cooldown, asks for nothing (`HOLD`), exits to `HOLD` and never straight to
a progression, and has its own explicit exit condition. An **idle** window never enters it — inactivity is
not failure — because the "reduced exposure" reading is only made about a window in which something was
observed at all.

### Why the recent half, and not the whole window

A window is history. A family that fell short three weeks ago and completes everything now has a whole-window
shortfall and has still earned its next step, so the gates that answer *"is the user handling this now?"*
(the progression shortfall, the regression shortfall and the recovery reading) are stated on the **newer
half** — the side of the comparison the trend itself was measured from. `ProgramAdaptiveSignals` reports
both halves and the whole window, so a policy can ask either question, and the halves exist exactly when
the trend does.

## 5. Evidence, confidence and recovery (§12, §14)

They are three inputs and three gates, and they are never combined:

| judgement | vocabulary | who states it | what the policy does with it |
| --- | --- | --- | --- |
| evidence | `INSUFFICIENT` / `STABLE` / `STRONG` | the caller, from its own history | a change needs `STRONG` |
| confidence | `LOW` / `MODERATE` / `HIGH` | the caller, separately | `LOW` blocks a change |
| recovery | `FAVORABLE` / `CAUTIOUS` / `UNKNOWN` | the caller, as §14's context | only `FAVORABLE` progresses |

There is no composite score anywhere, and the separation is measured rather than promised:
`ProgramAdaptivePolicyTest.confidenceIsASeparateJudgementFromEvidence` shows that `STRONG`+`LOW` and
`STABLE`+`HIGH` produce **different reasons** (`LOW_CONFIDENCE` vs `INSUFFICIENT_EVIDENCE`), so neither is
a relabelling of the other, and `aCautiousContextMakesProgressionConservativeAndInventsNothingElse` shows
that a cautious context holds a change without ever producing a regression. Missing evidence stays missing
throughout: an unmeasured trend is `null`, never `STABLE` and never `NEGATIVE`; a missed opportunity is not
an exposure and not a zero; an idle window is an absence of evidence, not a decline.

## 6. Progression ownership (§9, §15)

The target tree ships no progression ladder, and this stage **does not invent one**:

```text
ProgramProgressionRelation(familyId, variants: [ (level, exerciseId, prescription) … ])
```

is a **value the caller supplies**. That is the honest shape of the deficiency: the architecture names
progression, variant changes and ceilings, and the domain owns no exercise or family catalogue, so the
ladder has to arrive as data. What the type *does* enforce is what §15 requires of a relation:

* the levels are contiguous, so "the next rung up" is always either declared or genuinely the ceiling;
* a level may declare several variants, which is the only shape in which §15's `CHANGE_VARIANT` is a
  *bounded* change — both ends at the same level, both declared, neither harder nor easier;
* a step up (or down) is only ordered when the level above (or below) declares **exactly one** variant: a
  relation that declares two has not said which one, and the engine holds instead of choosing;
* every variant states its own prescription, because the stage's whole output is a *presentation* and a
  variant nobody stated a prescription for cannot be presented. Keeping the current prescription instead
  would claim that a harder variant prescribes exactly what an easier one did.

Resolution rules, all of them bounded non-progressing answers rather than substitutions:

| situation | result |
| --- | --- |
| the family is at its top level | `HOLD` / `CEILING_REACHED` |
| the family is at its bottom level | `HOLD` / `FLOOR_REACHED` |
| the ordered step's exercise is not in `availableExerciseIds` | `HOLD` / `PROGRESSION_UNAVAILABLE` |
| the level above declares several variants | `HOLD` / `PROGRESSION_UNAVAILABLE` |
| the level is unknown (the caller holds none, and the relation does not declare the presented exercise) | `HOLD` / `PROGRESSION_UNAVAILABLE` |
| the element presents something the family does not declare there, and it declares another variant that is available | `CHANGE_VARIANT` / `VARIANT_REALIGNED` |

Nothing is ever substituted: the engine cannot return an exercise the relation does not declare, and it
cannot bypass the user's own selection to find one — the user's configuration stays authoritative (§9).

## 7. The aggregate load guard (§18)

```text
baseline session  vs  candidate session
        +  recent context
```

The guard is a **component**, exercised on its own at every scope §18 names
(`EXERCISE`, `FAMILY`, `FOCUS`, `SESSION`), and the engine consults it once per decision at the decision's
own scope. Its input is three profiles: the element as the plan presents it, the element as the change
would present it, and the caller's own recent context pair from the snapshot.

| dimension | channels | rule |
| --- | --- | --- |
| volume | sets, repetitions, seconds | each channel separately; an automatic change may not add past its own tolerance |
| intensity | one channel per family | one decision may travel at most `guardAllowedLevelSteps` levels |
| density | working seconds, rest seconds | working time may not lengthen past its tolerance; rest may not shorten below its floor |
| exposure | opportunities, completed | an automatic change may never **add** an opportunity — exposure is the plan's and the calendar's (§33) |
| recent context | the caller's own baseline/recent pair | when the recent past met or exceeded everything the plan prescribes **and** still owes an opportunity that produced nothing, an automatic increase is refused |

What it refuses to do, and how that is checked:

* **no total, no conversion.** There is no scalar and no cross-unit arithmetic anywhere: the comparison
  reports eight channels, and a comparison between a repetition-based and a duration-based profile leaves
  both volume channels *incomparable* (`ProgramIncomparableReason.DIFFERENT_UNIT`) rather than bridging
  them (§17);
* **no scope mixing.** Two profiles at different granularities produce a comparison whose every channel is
  `DIFFERENT_SCOPE` — no number travels between them, and `isAboveBaseline` and
  `candidateIsAtOrAboveBaselineEverywhere` both read `false`, so nothing was established;
* **no invented regression.** The guard's verdicts are *approved*, *filtered* or *not its business*:
  `HOLD` changes nothing and `REGRESS` takes load away, so neither is guarded, and a filtered `PROGRESS`
  becomes a `HOLD` and never a `REGRESS`;
* **only automatic changes.** §18's guard never sees a user-authored or pinned element, because the engine
  does not even evaluate one — there is no path by which the guard could be said to have blocked the user's
  own choice;
* **an explicit, auditable refusal.** A filtered decision stays in the result with `NOT_APPLIED`, the reason
  `AGGREGATE_LOAD_GUARD`, the channels that produced the refusal, and the action that was asked for;
* **deterministic.** Every comparison holds its channels in one canonical order and its per-family channels
  in ascending family order, so the same three profiles always produce the same verdict and the same
  violation list, in the same order.

## 8. Adjustment semantics (§16)

```text
one concrete future Slot
  + one Program/Revision context
  + one plan occurrence
  + before → after
```

The engine produces an `AdaptiveAdjustment` — the domain's existing type — and nothing else:

* the occurrence identity is preserved: `before` and `after` name the same `programExerciseId`, so an
  adjustment changes one element and cannot quietly swap in a different one;
* a change that would change nothing is not an adjustment at all: the engine produces none, and `HOLD`
  produces none by construction;
* supersession is **by reference**: the request names the adjustment to replace, the new one carries that
  id, and the earlier adjustment is never rewritten — it keeps describing exactly what the user was shown;
* no revision is created and none is mutated. A decision carries the revision and the slot it was given;
* the effective presentation is the domain's own composition (`SlotPresentation.presentedWorkout`), which
  the caller applies: `ProgramRevision + AdaptiveAdjustment = EffectiveWorkout`.

### `CHANGE_REST` is explicitly unsupported

`REST_BASED` is named and unimplemented (§10), so a rest adaptation has **no expressible target**: there is
no rest field on a prescription and no rest adjustment to produce. A window that asks for one is answered
`HOLD` / `REST_CHANGE_UNSUPPORTED` and never faked — no new persistence field, no invented rest value, no
adjustment claiming a change the domain cannot state. The guard likewise does not guard it: the rest
*channel* has a rule (rest may not shorten), but a rest *change* is not this stage's to make.

## 9. What is explicitly left for P12 (§30 step 12)

Nothing in this stage is wired, persisted or shown. The list is deliberate and complete:

1. **Wiring the engine.** Nothing constructs a `ProgramAdaptiveRequest`: the revision's plan, the slot, the
   element's presentation and the family's classification come from `ProgramPlanRepository` /
   `ProgramScheduleRepository` / the session's own snapshot, and assembling them is the integration stage's
   work. `AppContainer` is untouched, and `ProgramAdaptiveArchitectureTest` is the test that will have to be
   revised deliberately when that changes.
2. **The progression ladder's data.** `ProgramProgressionRelation` is a caller-supplied value: which
   variants exist, at which levels, prescribing what — a family catalogue the app does not have yet. This
   stage states the shape and refuses to invent the content.
3. **The recent context's assembly and the session-scope pass.** §18 compares a *baseline session* against
   a *candidate session* at four scopes. The guard component already works at all four (its suite measures
   each), but the engine consults it at the decision's own scope, because a session-scope candidate needs
   every element's resolved change — i.e. the whole session, assembled by the caller.
4. **Persisting a decision and its adjustment.** `ProgramAdaptiveRepository.persistDecision` exists (PR 3)
   and takes both halves in one transaction; nothing calls it from here.
5. **The reason has no column — resolved by §30 step 12, deliberately.** §23's
   `program_adaptive_decision_record` stores the target scope and id, the action, the outcome, the
   evidence, the confidence, the recovery and the stamp. Step 12 audited the two options and took the
   schema addition: a *filtered* decision (`NOT_APPLIED` + `HOLD`) has exactly the shape of the
   eighteen holds, so reading its reason back as `AGGREGATE_LOAD_GUARD` would rest on the *write rule*
   rather than on a stored fact, and the mapping from an action to a token is a property of today's
   vocabulary rather than of the row. The version-10 → version-11 migration therefore adds
   `reason TEXT`, `AdaptiveDecision` carries the token (this stage already computes it), and the full
   argument is in `docs/PROGRAM_ADAPTIVE_INTEGRATION.md` §5.
6. **`AdaptiveState`'s stored vocabulary.** The family's state after a window is reported for the caller to
   store in `program_family_progression_state`. It is the vocabulary PR 3 already stores, so nothing has to
   change — but the *naming* of the two generations is §30 step 15's to collapse, not this stage's.
7. **Progress' `PROGRAM_PR`.** PR 9 deferred the record-against-a-comparable-context measure to the stage
   that owns a progression level; this stage defines the level's *representation* (a family's own ladder)
   but does not touch PR 9's measures. Wiring the deferred measure up is a follow-up for the owner.
8. **No decisions are made automatically anywhere yet.** The engine is callable; nothing calls it.

## 10. Determinism

```text
equal domain inputs  ⇒  the same decision, action, outcome, target, reason, before/after and ordering
```

Determinism is enforced at three levels, and all three are measured:

* **at construction**, where the values whose order carries meaning are canonical and an out-of-order one is
  *refused* rather than silently normalized: a relation's variants are held by (level, exercise id), an
  intensity profile's entries by family, a window's observations chronologically. The convention is the
  foundation's own (`IntensityLoad`, `AdaptiveInputSnapshot`);
* **in the derivation**, where the observations are re-ordered by their own stamps and occurrence identity
  before anything is counted, and the trend's split is a fixed position rather than a rounding choice;
* **in the result**, where the comparison's channels are canonical, and the whole decision is a value that
  can simply be compared — `ProgramAdaptiveDeterminismTest` builds the same request twice with its sets and
  maps in opposite insertion orders and asserts the two results are equal.

There is no `Random`, no clock, no hash-derived ordering, no `System.` call and no filesystem access
anywhere in the package (`ProgramAdaptiveArchitectureTest.nothingInTheEngineReadsAClockARandomSourceOrTheFilesystem`).
The moment a decision is stamped with is an argument.

## 11. Verification

Baseline, on pristine `origin/main` (`bf7b539`, the merge commit of PR #292), measured in this tree before
any edit with `:app:cleanTest :app:testDebugUnitTest --rerun-tasks` and the JUnit XML `timestamp`s checked
against `date -u`:

```text
248 classes / 2245 tests / 0 failures / 0 errors / 0 skipped
```

After this change, on the committed bytes, forced fresh the same way:

```text
254 classes / 2338 tests / 0 failures / 0 errors / 0 skipped
```

— exactly `+6 classes / +93 tests`: the six new suites (`ProgramAdaptivePolicyTest`,
`ProgramAdaptiveEngineTest`, `ProgramAggregateLoadGuardTest`, `ProgramAdaptiveSignalCalculatorTest`,
`ProgramAdaptiveDeterminismTest`, `ProgramAdaptiveArchitectureTest`) and nothing else.
`ProgramAdaptiveRig` is a fixture with no test method, which is why it is not in the count. The counts are
parsed from the JUnit XML and cross-checked against the sources:
`grep -rho '@Test' app/src/test/java | wc -l` = 2338 and `grep -rl '@Test' app/src/test/java | wc -l`
= 254, both equal to the parsed totals. The run was forced fresh (`cleanTest` + `--rerun-tasks`) with the
XML `timestamp`s at `2026-09-19T14:29:16`–`14:29:31` against `date -u` at `14:31:21` in the same
`BUILD SUCCESSFUL in 45s` window, and the same command was run twice on these bytes (`254 / 2338 / 0F 0E 0S`
both times).

```text
:app:cleanTest :app:testDebugUnitTest --rerun-tasks     2338 tests, 254 classes, 0F / 0E / 0S
:app:compileDebugKotlin                                 BUILD SUCCESSFUL
:app:compileDebugUnitTestKotlin                         BUILD SUCCESSFUL
:app:compileReleaseKotlin                               BUILD SUCCESSFUL
:app:compileReleaseJavaWithJavac                        BUILD SUCCESSFUL
:app:assembleDebug                                      BUILD SUCCESSFUL
```

(`lintVitalRelease` remains the known pre-existing failure — `res/values/themes.xml:2`'s
`ResourceCycle: Theme.Material3.DayNight.NoActionBar extends itself` and the `ExpiredTargetSdkVersion`
error — so the release evidence is `compileReleaseKotlin` + `compileReleaseJavaWithJavac` +
`assembleDebug`, exactly as the earlier stages recorded it. It is not a P11 failure.)

## 12. RED evidence

`scripts/program-adaptive-engine-red-mutations.sh` applies one mutation at a time to the *production*
sources, reruns the focused adaptive-engine suite, restores the file and proves the restoration by
`md5sum -c`. A rule is only proven if breaking it fails a test.

```text
19 caught, 0 missed — control: the un-mutated tree stays GREEN
every mutated production source restored byte-identically (md5sum -c: 8 of 8 files "OK")
```

| mutation | expectation |
| --- | --- |
| confirmation windows ignored | caught |
| the progression cooldown always elapsed | caught |
| recovery gating removed (priority) | caught |
| recovery entry removed | caught |
| the recovery exit gate never met | caught |
| recovery exits straight into a progression | caught |
| the regression shortfall threshold removed | caught |
| the attendance gate removed | caught |
| a `HOLD` reports a progression | caught |
| a user-authored element is adapted | caught |
| adjustment occurrence identity broken | caught |
| the ceiling and the floor stop being boundaries | caught |
| profiles at different scopes compared | caught |
| unit separation removed | caught |
| the relation keeps whatever order it was handed | caught |
| guard filtering removed | caught |
| the guard rules on a regression | caught |
| the recent-context rule loosened | caught |
| control: the un-mutated tree stays GREEN | not caught (as required) |

Three claims are deliberately **not** expressed as mutations, because no single line carries them, and the
script says so in its own output:

* **"the engine reads no clock and no random source"** is a property of the whole package, so the
  architecture suite scans every engine source for `Random`, `shuffled`, `currentTimeMillis`, `Instant.now`,
  `Clock`, `UUID`, `hashCode()` and `System.`;
* **"the engine persists nothing and creates no revision"** has no line to break: the engine holds no
  repository, no DAO, no transaction and no id source, so it is asserted structurally (the engine declares
  no field at all, and the result's own field set is pinned);
* **"a REST adaptation is not faked"** has no production target to mutate either: the policy reports it
  unsupported and the resolution has nothing to produce, asserted behaviourally.

## 13. Claim → test

| claim | suite |
| --- | --- |
| insufficient evidence holds, and says so | `ProgramAdaptivePolicyTest.insufficientEvidenceHoldsAndSaysSo` |
| no history is not a failure | `…aWindowWithNoHistoryIsNeverReadAsAFailure` |
| a stable plateau holds; a mixed window is a mixed one | `…aStablePlateauHolds`, `…aWindowThatPointsTwoWaysAtOnceIsAMixedOneAndNotADirection` |
| confirmed positive evidence progresses | `…confirmedPositiveEvidenceProgresses`, `ProgramAdaptiveEngineTest.aConfirmedProgressionMovesTheElementToTheNextDeclaredVariant` |
| a direction before its confirmation holds | `…positiveEvidenceBeforeItIsConfirmedHolds` |
| the first change after `null` is possible | `…aConfirmedChangeIsPossibleWhenNoChangeHasEverBeenRecorded` |
| the cooldown blocks an earned change and reports itself | `…theCooldownBlocksAnEarnedChangeAndSaysSo`, `…aCooldownThatHasElapsedDoesNotBlockTheChange` |
| confirmed negative evidence regresses; one bad result does not | `…confirmedNegativeEvidenceRegresses`, `…aSingleBadResultDoesNotRegressAFamily`, `…anUnconfirmedDeclineHoldsRatherThanRegressing` |
| recovery entry, priority and exit | `…recoveryIsEnteredOnUnabsorbedLoadAndOutranksTheCooldown`, `…recoveryOutranksAConfirmedProgression`, `…recoveryExitsToHoldAndNeverStraightToAProgression`, `…anIdleWindowNeverEntersRecovery` |
| evidence, confidence and recovery are three gates | `…confidenceIsASeparateJudgementFromEvidence`, `…aCautiousContextMakesProgressionConservativeAndInventsNothingElse` |
| the ceiling stops a progression; the floor a regression | `ProgramAdaptiveEngineTest.theCeilingStopsTheProgressionAndSaysSo`, `…theFloorStopsTheRegressionAndSaysSo` |
| an unavailable step is not substituted | `…aStepTheUsersSelectionExcludesIsUnavailableAndNothingIsSubstituted`, `…aStepThatIsDeclaredButNotSingleIsUnavailableRatherThanChosenByTheEngine` |
| a variant change is a declared same-level move | `…anElementItsFamilyDoesNotDeclareIsRealignedToTheDeclaredVariant`, `…aVariantTheUserNoLongerHasIsRealignedToAnotherTheFamilyDeclaresAtTheSameLevel` |
| the user's own content is never adapted | `…anElementTheUserOwnsIsNeverAdaptedAtAll`, `…anElementTheUserAuthoredIsNotRealignedEither` |
| the guard refuses an excessive increase and never invents a regression | `…anIncreasePastTheToleranceIsFilteredAndBecomesAHold`, `ProgramAggregateLoadGuardTest.theGuardNeverReturnsARegression`, `…onlyAChangeIsGuardedAndNeverACondition` |
| the guard's tolerances are exact and channel-specific | `ProgramAggregateLoadGuardTest.anExcessiveAutomaticIncreaseIsFilteredAndNamesTheChannel`, `…theToleranceIsTheExactIntegerBoundaryThePolicyStates`, `…aLongerWorkingTimeIsRefusedOnItsOwnChannel`, `…restMayNotShortenEvenThoughNoOtherChannelMoved`, `…anAutomaticChangeNeverAddsAnOpportunity`, `…aJumpOfMoreThanTheAllowedNumberOfLevelsIsRefused` |
| incompatible scopes and units are never compared | `…profilesStatedAtDifferentScopesAreNeverCompared`, `…aChangeWhoseUnitsAreNotComparableIsNotRefusedOnTheirAccount`, `…aLevelIsOnlyComparedInsideItsOwnFamily` |
| every scope §18 names is compared at its own granularity | `…everyScopeSectionEightNamesCanBeComparedAtItsOwnGranularity` |
| the recent context is respected | `…aRecentContextThatMetThePlanAndOwedNothingDoesNotBlock`, `…aRecentContextAtOrAboveThePlanWithAnUnmetOpportunityBlocksTheChange`, `…aRecentContextBelowThePlanDoesNotBlock`, `…aMissingRecentContextIsMissingAndNotAnAssumption` |
| the guard's context rule reaches the engine | `ProgramAdaptiveEngineTest.aRecentContextStillOwingWorkFiltersARealignmentThroughTheGuard` |
| `HOLD` produces no adjustment | `ProgramAdaptiveEngineTest.aStableWindowHoldsAndProducesNoAdjustment` |
| the adjustment keeps its occurrence identity, changes something, supersedes by reference | `…anAdjustmentChangesSomethingAndKeepsTheOccurrenceIdentity`, `…anAdjustmentSupersedesByReferenceAndTheEarlierOneIsNeverRewritten` |
| a decision carries its slot, its revision and its moment | `…theDecisionCarriesTheSlotTheRevisionAndTheMomentItWasGiven` |
| no history ≠ failure, missed ≠ zero, inactivity ≠ decline | `ProgramAdaptiveSignalCalculatorTest.aTrendIsOnlyReportedOnceItHasBeenMeasured`, `…anIdleWindowIsIdleAndNotADecline`, `…aMissedOpportunityIsNotAnExposureAndNotAZero` |
| the trend is family-scoped and read from the recent end | `…theHistoryOfAFamilyIsCountedFromItsOwnOccurrencesAndFromNothingElse`, `…anExerciseTheClassificationDoesNotKnowIsItsOwnFamily`, `…theTrendReadsWhatTheNewerHalfAchievedAgainstWhatTheOlderHalfWasAskedFor`, `…anOddWindowIsSplitAtAFixedPositionAndTheNewerHalfTakesTheLargerShare` |
| attendance is measured over the plan's own opportunities | `…attendanceIsMeasuredOverTheOpportunitiesThePlanItselfOffered`, `ProgramAdaptivePolicyTest.aWindowWhoseOpportunitiesWentUntakenDoesNotProgress` |
| determinism | `ProgramAdaptiveDeterminismTest` (five tests: same request, collection order, canonical construction, comparison order, guard verdict order), `ProgramAdaptiveSignalCalculatorTest.theSignalsAreAFunctionOfTheWindowAndNotOfACollectionOrder` |
| the engine is pure, stateless and unreachable from the platform, the data layer, the UI — and from the legacy generation | `ProgramAdaptiveArchitectureTest` (eleven tests), `ProgramDomainPurityTest` (the package is added to its scans), `AdaptiveDomainPurityTest` (the scan now reads every nested package) |

## 14. Owner decisions and underdetermined semantics

1. **The two guards are one component, consulted at the decision's scope.** §18 names four scopes and the
   engine decides at one of them (`EXERCISE` for a variant change, `FAMILY` for a level move). The
   session-scope pass is available through the same component and is left to the integration stage, because
   a candidate *session* needs every element's resolved change (see §9.3).
2. **The energy of the reason vocabulary is deliberate.** Eighteen hold reasons and three change reasons,
   each the answer to one rule. The alternative — one `HOLD` with an optional detail string — would put
   user-facing text (or a free-form field) where a stable domain token belongs (§25).
3. **v1 allows no automatic volume increase** (`guardAllowed*IncreaseNumerator = 0`). The stage's automatic
   change is *which variant the family is on*, from the family's own ladder; a bounded increase is a policy
   edit away (`+25%` is `1/4`), and the arithmetic is exact either way.
4. **A same-level realignment is not a level move and not gated by the cooldown.** It is a repair: the
   element presents something the family does not declare there (or the user no longer has), and another
   declared variant at the same level is available. It keeps the family's state at `HOLD`, and it is
   refused by the guard like any other automatic change.
5. **A relation that declares several variants above the current level produces a `HOLD`.** The relation has
   not said which one the family should move to, and choosing would be the engine inventing a preference.
6. **The reason is not persisted, because §23's decision table has no column for it** (§9.5).
7. **`AdaptiveState` is reused as the stored state vocabulary** rather than re-declared, because PR 3's
   schema stores it (§2, §9.6).
8. **The engine returns a result; it does not persist.** The pair (decision, adjustment) is exactly what
   `ProgramAdaptiveRepository.persistDecision` takes, so the integration stage's call is one line — and the
   engine remains callable from a test with nothing behind it.

## 15. Base

This branch is based on `origin/main` at `bf7b539`, the merge commit of PR #292
(`feat/program-generated-planner`), whose tree is identical to its predecessor branch tip's
(`a082c64`): no rebase was needed and no prior stage's file was rewritten.
