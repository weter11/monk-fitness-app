# Program System — the Adaptive Integration (PR 12)

Scope: **§30 step 12**, *Adaptive integration*. The **application layer** that turns a completed Session
into the adaptive half of §27's completion unit: the target opportunity, the window of real history,
the three judgements the engine takes as inputs, the request the pure engine is asked with, the four
shapes of its answer, and the persistence the completion transaction performs.

Reference architecture: `docs/Monk Fitness — Program System Implementation Blueprint.MD` (cited below as
**§N**). Companions: `docs/PROGRAM_DOMAIN_FOUNDATION.md` (PR 1), `docs/PROGRAM_ROOM_SCHEMA.md` (PR 2),
`docs/PROGRAM_SCHEDULE_FREQUENCY_CORRECTION.md` (PR 2.1), `docs/PROGRAM_DATA_ACCESS.md` (PR 3),
`docs/PROGRAM_COMPOSITION_ROOT.md` (PR 4), `docs/PROGRAM_LIFECYCLE.md` (PR 5),
`docs/PROGRAM_MANUAL_EDITOR.md` (PR 6), `docs/PROGRAM_SCHEDULER.md` (PR 7),
`docs/PROGRAM_SESSION_RUNTIME.md` (PR 8), `docs/PROGRAM_PROGRESS_HISTORY.md` (PR 9),
`docs/PROGRAM_GENERATED_PLANNER.md` (PR 10), `docs/PROGRAM_ADAPTIVE_ENGINE.md` (PR 11).

---

## 1. The graph

```text
stored Program facts (revision, plan day, slots, sessions, set logs, adaptive state)
        ↓
completed Session                                          §6
        ↓  the families it exposed, and their window of real history
future target Slot  —  next not-yet-started, still-startable opportunity,
                       same Program, same Revision, strictly after the decision's own day   §4
        ↓  its presentation: the revision's plan day + the adjustments standing for it       §16
        ↓  the element the family's ladder can resolve                                       §10
AdaptiveInputSnapshot + ProgramAdaptiveRequest                                                §5, §8
        ↓
ProgramAdaptiveEngine.decide                (pure: no collaborator, no clock, no storage)     §11
        ↓
ProgramAdaptiveResult                       (decision, adjustment, family state)
        ↓
AdaptiveIntegrationOutcome                  (§20's four shapes)
        ↓
SessionRuntime.finishSession(sessionId, completion)   — one transaction:                §27
        session + slot + adaptive state + decision + adjustment
        ↓
future Slot's Session Snapshot  — the adjustment is consumed there, once                     §16, §19
```

```text
ViewModels / screens   ← untouched: §23 of the brief forbids UI in this stage
GeneratedPlanner / FocusPlanner / PlanReconciler / ProgramScheduler   ← untouched, and not called
AppContainer           ← one construction added (the integration), plus two declared-empty ports
```

| file | layer | responsibility |
| --- | --- | --- |
| `domain/usecase/ProgramAdaptiveIntegration.kt` | use case | the pass: target, window, judgements, request, outcome — writes nothing |
| `domain/adaptive/integration/AdaptiveTargetSlot.kt` | pure domain | §4's target rule and §10's element choice, as functions |
| `domain/adaptive/integration/AdaptiveInputGap.kt` | pure domain | the **expected** absences, each its own typed token |
| `domain/adaptive/integration/AdaptiveIntegrationOutcome.kt` | pure domain | §20's four shapes, and where the reason is kept |
| `domain/adaptive/integration/AdaptiveIntegrationResult.kt` | pure domain | §28's classes: an outcome, invalid data, a failure |
| `domain/adaptive/integration/AdaptiveJudgement.kt` | pure domain | the three inputs §12/§14 take, derived from target facts |
| `domain/adaptive/integration/AdaptiveWindowRule.kt` | pure domain | §8's lookback interval, owned here and not by the engine |
| `domain/adaptive/integration/ProgressionRelationProvider.kt` | pure domain | §10's ladder boundary and §9's family boundary, plus their explicit empty values |
| `domain/workout/SessionCompletion.kt` | pure domain | `AdaptiveCompletion`: the three shapes of the adaptive half, with the state leg |
| `domain/usecase/SessionRuntime.kt` | use case | §27's unit, the future-opportunity validation contract, and the state leg |
| `domain/workout/SessionRuntimeResult.kt` | pure domain | `AdaptiveTargetRefusal` and the refusal that replaces the P8-era same-slot rule |
| `domain/program/WorkoutSlot.kt` | pure domain | `isStartable` — the fact the target rule and the start path share |
| `data/model/FamilyProgressionStateEntity.kt` · `AppDatabase.kt` · `AdaptiveMappers.kt` | data | §11's window bookkeeping and §13's reason token, with the version-10 → version-11 migration |
| `di/AppContainer.kt` | composition root | the integration's construction, and the two ports it declares empty |
| `data/repository/ProgramAdaptiveRepository.kt` | data | unchanged in shape: it stores what it is handed, in the completion's transaction |

## 2. Source Session versus future target Slot — the contract §30 step 12 corrects

§30 step 8 landed the completion seam before anything produced a decision, so its check was *"the
decision's slot equals the session's slot"*. With a real adaptive producer that equality is the opposite
of the rule, and this stage replaces it deliberately:

```text
P8        decision.slotId == session.slotId     (nothing produced a decision, so a completion could
                                                 only be handed one about its own opportunity)
step 12   decision.programId == session.programId
          decision.revisionId == session.revisionId
          decision.slotId != session.slotId        §4: the completed opportunity is never adapted
          the target slot is stored and of this plan
          target.plannedFor is strictly after the decision's own day   ← the producer's rule
          the target slot is not already started, and is still startable
```

**The producer and the consumer check the same rule, and share one calendar.** The integration chooses
the target with `LocalDate.ofInstant(capturedAt, zone)`, the runtime checks it with
`LocalDate.ofInstant(decision.decidedAt, zone)` — and `capturedAt` *is* the decision's `decidedAt`, so the
two comparisons are the same comparison. Both read the **one** `ZoneId` the composition root holds and
hands to each of them, which is what the brief means by *"use the same `ZoneId` that defines the
calendar-day semantics of P12, so that producer and consumer have the same semantics"*: the zone is a
required argument on both sides, neither acquires one (`ZoneId.systemDefault()` appears in `AppContainer`
and nowhere else on this path), and no side reads the clock a second time — the consumer validates the
decision against the moment the decision itself carries.

The refusal vocabulary was renamed with the rule: `AdaptiveDecisionIsOfAnotherOpportunity` became
`AdaptiveDecisionIsNotAboutAFutureOpportunityOfThisCompletion`, and it carries an
`AdaptiveTargetRefusal` that names **which clause** was broken (`ANOTHER_PROGRAM`, `ANOTHER_REVISION`,
`THE_COMPLETED_SLOT_ITSELF`, `NO_SUCH_SLOT`, `SLOT_IS_OF_ANOTHER_PLAN`, `SLOT_IS_ALREADY_STARTED`,
`SLOT_IS_NOT_AHEAD_OF_THE_USER`). The P8 suite's same-slot test was **replaced** — not deleted — by five
tests over the new invariant, three of which assert that the *whole database* is unchanged when the
completion is refused.

## 3. Which future Slot, and why only that one

```text
the next not-yet-started, still-startable opportunity
of the same Program
under the same Revision the completed Session belonged to
whose planned day is strictly after the decision's own day
```

Every clause is load-bearing, and the last one is the one that keeps the rule *temporal*:

* **same Revision.** Adaptive state is revision-scoped (§4, §18): a newly saved revision starts from its
  own baseline, and an edit must not let a session that ran under an old plan adapt a different one. The
  revision is also what makes the adjustment *applicable* — `before`/`after` are elements of a plan day,
  and a later revision mints new day and element identities, so an adjustment can only be composed
  against the revision that presents the element it changes;
* **not yet started.** An adjustment is consumed when a slot's session snapshot is taken (§16), so an
  opportunity that already has an attempt can no longer present it;
* **still startable.** A taken or withdrawn opportunity is not ahead of the user. `WorkoutSlot.isStartable`
  is the domain's own statement of that fact, shared with the runtime's `startRefusal` so "which
  opportunities are ahead" is one rule rather than two lists;
* **strictly after the decision's own day.** A `MISSED` opportunity **is startable** — the runtime begins
  one, and §20 neither slides a missed workout nor forbids training it late — so *status alone would let
  the adaptive stage target a past day*: an opportunity the user has already lived through, whose
  adjustment would then sit unconsumed while the next real session is started. "Future" therefore has to
  mean a date, and the date is read in the injected zone. The boundary is exclusive on the decision's own
  day: an opportunity planned for *today* may already be under way, so the rule takes the first one
  planned after it — **and the runtime refuses a decision that breaks it**
  (`AdaptiveTargetRefusal.SLOT_IS_NOT_STRICTLY_AHEAD_OF_THE_DECISION`), checked against the decision's own
  moment rather than against a fresh reading of the clock, so the two ends of §4's contract cannot read
  different days when they are handed the same zone.

It creates no slot, moves no date, renumbers no plan day and reschedules nothing (§25): a past-but-
startable opportunity is *excluded*, never slid — sliding a missed workout is the Scheduler's business.

**When nothing is eligible** the answer is `AdaptiveInputGap.NO_FUTURE_SLOT`, and *nothing* adaptive is
written: no decision row, no adjustment and no state row, because no window was evaluated. That is the
ordinary end of a program and an expected result, not an exception and not a fabricated `NOT_APPLIED`.

### Which element, and whose it is

The subject of a decision is the family the completion just trained — that is what its window, evidence
and signals are about — so the element is the first element **of the target opportunity's presentation**,
in the presentation's own order, whose family is one the completion exposed and for which a ladder is
declared. The presentation is composed by the domain's own `presentedWorkout` (the revision's plan day
plus the adjustments still standing for the opportunity), so an element that already carries an
adjustment is decided about **as the user would see it**, and the adjustment a new decision supersedes is
the one that stands for that very element. A day that trains no exposed family is not adapted *now*: the
family's window opens again when its own next appearance is completed.

Ownership is **not** decided here. The element carries what the plan says about it — `isPinned` and
`origin`, in that order of precedence — and the engine's own rule holds on a user-authored or pinned
element without evaluating anything else (§15, §18). Skipping a user-owned element to reach the next
automatic one would be this layer answering the question the engine already answers, and it would
silently adapt an element *after* the user's own choice had been considered and skipped.

## 4. The window is built from real facts

```text
observations   one per occurrence with at least one confirmed set, from the Sessions' own snapshots
               (a completed Session and a cancelled one both contribute; the triggering attempt
               contributes its own confirmed sets, which is what makes §27's single transaction possible)
plan facts     the revision's opportunities whose date falls inside the window, and what they prescribed
performance    the sets, repetitions and seconds the Sessions actually confirmed
state          the family's stored position, counts and cooldown; the two load profiles; the judgements
```

The rules §12 makes about exposure are the rules this assembly applies: full execution is a full
observation, partial execution a partial one, a skipped exercise and an untrained opportunity produce
**no** observation rather than a zero, and a cancelled Session keeps its partial work while never
becoming a full exposure. The prescriptions an observation is measured against are the Session's **own
snapshot**, so a later revision or adjustment cannot change what an old observation says.

Two consequences worth stating plainly:

* **nothing is inferred from the plan.** A completion whose prescription was never confirmed exposes no
  family at all (`NO_EXPOSED_FAMILY_IN_THE_TARGET_SLOT`), where a window built from prescribed values
  would have found the family exposed and decided something about it. The suite asserts exactly that
  falsification;
* **the recent side of §18's comparison is `null` when the window holds no confirmed work** — a fact, not
  a zero. Reporting an untrained window as a zero-load recent past would read as *"the user did nothing,
  so add more"*.

### The three judgements, and what is deliberately not one

`AdaptiveJudgementRule` derives the evidence, confidence and recovery levels the engine takes as inputs
from the window's own signals, and keeps them the three separate answers §12 and §14 state:

```text
evidence    the policy's own exposure buckets — nothing measurable, a picture, a direction
confidence  how many comparable exposures, and from how many distinct Sessions
recovery    whether the recent context is above the plan or the plan's opportunities went unattended
```

There is **no composite score** and no medical metric. Two §13/§14 inputs have no target-side source and
are therefore absent from the rule rather than faked: the user's per-set difficulty feedback
(`EASY`/`OK`/`HARD`, §13 — no stored column) and an explicit user caution (§14 — no UI and no storage).
An idle window is `UNKNOWN`; it is never turned into `FAVORABLE`.

## 5. Where the reason is kept, and why it is not reconstructed (§13)

The decision row stores the engine's own reason token
(`program_adaptive_decision_record.reason`, added by the version-10 → version-11 migration). The audit
that produced that choice, because the alternative was available and is not enough:

```text
APPLIED     + PROGRESS       → the row's action names exactly one change token: SUSTAINED_POSITIVE
APPLIED     + REGRESS        → ... and SUSTAINED_NEGATIVE
APPLIED     + CHANGE_VARIANT → ... and VARIANT_REALIGNED
NOT_APPLIED + HOLD           → ??? one of nineteen hold tokens, of which the row stores none
```

* **the filtered case has no answer in the row.** A guard-refused decision is stored as `NOT_APPLIED` +
  `HOLD` — the identical shape to `AWAITING_CONFIRMATION`, `STABLE_PLATEAU`, `CEILING_REACHED`,
  `USER_AUTHORED_ELEMENT` and the rest. Reading it back as `AGGREGATE_LOAD_GUARD` is a claim about *the
  write rule*, not about the stored fact, and it would silently change the meaning of **historical** rows
  the day another `NOT_APPLIED` shape is persisted;
* **the other three lines rest on the current vocabulary.** *"Each change action names exactly one
  reason"* is a property of today's `ProgramAdaptiveReason`, not an invariant of the type: nothing
  forbids a second token whose action is `PROGRESS`, and adding one would re-label every previously
  stored applied decision. A stored token cannot be re-labelled;
* **the facts a reconstruction would want are deliberately not persisted** — the requested action, the
  signals, the guard's own per-channel verdict — because they are functions of the window the decision
  was taken on and the window is reconstructible from the Sessions. The reason is not.

The three shapes are enforced rather than documented: `AdaptiveApplied` requires a **change** reason,
`AdaptiveFiltered` requires `AGGREGATE_LOAD_GUARD`, and `NothingToAdapt` requires a **hold** reason — so a
decision whose reason disagrees with its outcome is not producible at all, and the `NOT_APPLIED` row that
reaches storage always says `AGGREGATE_LOAD_GUARD`. What stays unpersisted is recorded here as a boundary
rather than hidden: **the guard's own sub-reason** (which channel exceeded its tolerance, or that the
recent context met the plan while owing an opportunity) is not a column. It is recomputable from the
window and the policy, which is exactly why it needs none — but a reader of the trail sees the *decision's*
reason, not the channel.

## 6. The family's state (§11) — what is stored, and why each field is

The five columns appended to `program_family_progression_state` are the engine's own window facts
(`ProgramAdaptiveWindow`). A window cannot count itself, so the caller carries them:

| field | what it is | can it be reconstructed from the decision trail + Sessions? |
| --- | --- | --- |
| `precedingProgressQualifyingWindows` | consecutive preceding windows in which §7's progression conditions held | **no.** The trail holds applied and guard-filtered decisions only; a hold is a reading and is deliberately not written (§12), so the trail is not a window log. The per-window verdict is not a field of any decision row either — `PROGRESSION_COOLDOWN` deliberately does not say which direction it is holding |
| `precedingRegressQualifyingWindows` | the same, for the regression conditions | **no**, for the same reason |
| `precedingRecoveryQualifyingWindows` | the same, for §14's reduced-absorption pattern | **no**, for the same reason |
| `qualifyingWindowsSinceLastChange` | the cooldown position: eligible windows since the last level change, or `null` when none ever happened | **no** — the count of *windows* is not derivable from the rows a hold does not write. `null` is not `0`: the cooldown exists to stop oscillation *after* a change, so the first earned change has none to serve |
| `recoveryQualifyingWindows` | windows completed in the family being in recovery — §14's exit gate | **no**: it is a count of windows, and the recovery state is on the same row |

The alternative shape — persisting a row for **every** window and reducing it at read time — was
considered and rejected: §12 says a hold is not a record, and writing one per completed workout would
bury the two decision shapes §16 and §18 require in noise while still not carrying the per-window
verdicts a reconstruction would need.

They are not the Stage-1 `FamilyAdaptationState`'s counters copied over: those are scoped by the legacy
revision integer and describe the pilot's own state machine, which the target tree does not share. **No
policy-version column was added**, because nothing reads one: the policy is a value the caller supplies
(`ProgramAdaptivePolicy.V1`), and a stored version with no reader is a column that can only disagree.

### Why a held window still advances the state

A `NothingToAdapt` outcome writes the family's state and **no** decision row. That follows from arithmetic
the policy states: a direction is confirmed only when it holds in `progressConfirmingWindows`
**consecutive** windows, so the count has to advance through the windows that changed nothing — if only
applied and filtered windows advanced it, the counter could never reach the confirmation count and the
adaptive stage could never progress a family at all. The state row is not a decision record: it is §23's
one *current* row per family per revision, and a family that has been measured has a current state
whether or not anything was changed about it. §12's *"no decision means nothing"* is honoured where it is
meant: a pass that could not be built (no eligible slot, a manual plan, no ladder, no classification)
writes **nothing at all** — not even a state row.

## 7. §27's unit of work

`SessionRuntime.finishSession` opens one transaction and writes, in order: the session, the opportunity,
the decision with its adjustment, and the family's state. Every leg is proved to roll back together:

```text
session write fails        → nothing: no completion, no slot stamp, no adaptive row
slot write fails           → the same
decision insert fails      → the same
adjustment insert fails    → the same
family state write fails   → the same
```

The planted faults are the DAO doubles' own (`ProgramDaoFaults`), so the rollback is measured on a real
SQLite engine and a whole-database census rather than argued from the code's shape. The transaction runner
is the application's, not the engine's: the engine holds no repository, no DAO and no transaction.

## 8. The capability boundary (what production does *not* do yet)

This stage wires two ports and **declares both empty**, and that is the honest state of the target tree
rather than a placeholder:

```text
ProgressionRelationProvider      → NoDeclaredProgression             no family ladder is persisted
ExerciseFamilyClassification     → NoExerciseFamilyClassification    no exercise→family map is persisted
```

Neither fact exists anywhere in §23's schema. The only ladders the repository has ever held are the
Stage-1 pilot's (`PilotProgressionProfiles`), which §30 step 11 forbids this generation to reach for and
which are scoped to the legacy program's own axis. So **in production, no family is adapted today**: every
pass stops at `NO_DECLARED_PROGRESSION_RELATION` (or `NO_FAMILY_CLASSIFICATION` when the day's elements
are unclassified), nothing is written, and **nothing is fabricated to make a family look adaptable** —
no ladder is invented, no difficulty order is inferred from exercise ids, no family is inferred from a
name, and no default relation exists.

Two artefacts have to arrive, and the ports are where they arrive:

```text
1. a persisted family ladder: which variants exist, at which positions, prescribing what;
2. a persisted exercise→family catalogue, and §9's set of exercises the user's own configuration enables.
```

Three further boundaries are stated rather than implied:

* **§9's `availableExerciseIds`** is read as *the revision's own presented exercises* — the only
  selection fact the target schema stores. A ladder step whose exercise the plan does not present is
  therefore unavailable and the engine holds rather than introducing an exercise the user's plan does not
  contain. A persisted per-user enabled-exercise selection would widen it;
* **`restChangeRequested` is always false.** §10's `REST_BASED` has no subtype, no plan element prescribes
  rest and no screen can ask: the policy's unsupported-rest path stays reachable only from a caller that
  states it;
* **the guard's own sub-reason is not persisted** (see §5).

## 9. `PROGRAM_PR` — deferred, with the exact remaining blocker (§24)

PR 9 deferred `ProgressMeasure.PROGRAM_PR` because *"a record needs a comparable context that says what a
record is"*, and the target model had no progression level. There is now a level — the family's ladder
(§15) and the family's stored position (this stage) — so the question is worth answering properly, and the
answer is that it is **still not computable cleanly**:

1. **a level is not a stored fact of an attempt.** `program_family_progression_state` holds the family's
   *current* position, and §11 deliberately makes it mutable current state; the level an attempt was made
   at is not recorded on the decision, the adjustment or the snapshot. So "the level this performance
   happened at" — the context a record would have to be measured against — does not exist in storage;
2. **the ladder itself is not persisted.** Even the *order* of a family's variants is a caller-supplied
   value today (`ProgressionRelationProvider`), so a level number is not comparable across time at all
   until a ladder is stored;
3. **the remaining comparable context is the plan element's own prescription** — which the plan may
   change in any revision, so a "record" against it would be a record against a moving target; and the
   measure that *is* well defined against it (completion against the prescription) is what PR 9's
   existing measures already compute;
4. **global PR must not become Program-owned** (§21): a Program-context PR would have to be stated as
   *"the best performance of this element/level within this Program"*, and with (1)–(3) unmet that
   statement has no denominator.

**No change was made to the Progress layer.** The blocker is precise and small: store the ladder, and
record the level an attempt was made at (or reconstruct it from a stored ladder plus the adjustment
chain — which needs the ladder first). Until then, `PROGRAM_PR` stays deferred rather than being
implemented as a heuristic.

## 10. What remains for P13

* **Import / Export / Share** (§30 step 13): untouched here — no DTO, no mapper, no version, no file
  format. The adaptive facts this stage stores (`reason`, the five counts, the decision and adjustment
  rows) are target-program data and become P13's to carry;
* **UI** (§30 step 14) and **legacy removal** (§30 step 15): untouched, and asserted as untouched by the
  architecture suite (no ViewModel or screen names the stage; the Stage-1 files are still there and still
  name nothing this stage adds).

## 11. Verification

Baseline, on pristine `origin/main` (`18e4965`, the merge commit of PR #293), measured in this tree
before any edit with `:app:cleanTest :app:testDebugUnitTest --rerun-tasks` and the JUnit XML
`timestamp`s checked against `date -u`:

```text
254 classes / 2338 tests / 0 failures / 0 errors / 0 skipped
```

After this change, on the committed bytes, forced fresh the same way — see §12 for the two counts and
the four new suites. Migration version: **11** (`MIGRATION_10_11`: five window-bookkeeping columns on
`program_family_progression_state`, and `reason` on `program_adaptive_decision_record`).

## 12. RED evidence

`scripts/program-adaptive-integration-red-mutations.sh` applies one mutation at a time to the production
sources, reruns the focused §30-step-12 suites, restores the file and proves the restoration by
`md5sum -c`. A rule is only proven if breaking it fails a suite.

```text
22 caught, 0 missed — control: the un-mutated tree stays GREEN
every mutated production source restored byte-identically
```

The script **pre-flights the tree before it takes its baseline** — every mutation's anchor must be
present and no mutation's replacement may already be there — takes an exclusive lock so two runs cannot
touch one tree, and aborts if a mutation cannot be applied. That is not decoration: two concurrent runs
once restored each other's mutations, and a baseline taken on a mutated file makes `md5sum -c` "prove" a
restoration of the mutated bytes while the control row is quietly RED.

| mutation | rule it breaks |
| --- | --- |
| the selection admits the completed and the past opportunities | §4's target rule |
| the temporal clause is inverted | a past opportunity is never the target |
| the temporal clause is dropped | status alone cannot decide what is ahead |
| an opportunity that is no longer startable is a candidate | §4, §19 |
| an opportunity whose snapshot is taken is a candidate | §16's consumption |
| the program boundary is not checked | §4, §16 |
| the revision boundary is not checked | §4, §18 |
| the completed slot may receive the decision | §4's corrected contract |
| the temporal clause is dropped at the runtime's own boundary | §4, and the producer/consumer agreement |
| a withdrawn opportunity may receive the decision | §4 |
| the guard refusal is treated as an ordinary hold | §18 |
| a held window is recorded as a decision | §12, §16 |
| the window bookkeeping is not advanced | §11 |
| a partial exposure is recorded as a full one | §12, §13 |
| the element choice fabricates a family from the exercise id | §9, §10 |
| the supersession link is dropped | §16 |
| the stored reason is a constant | §13, §22 |
| the family-state leg leaves the completion transaction | §27 |
| the start ignores the standing adjustments | §16, §19 |
| a user-owned element is adaptable | §15, §18 |
| the MANUAL-mode gate is removed | §19 |
| the target integration is wired to the Stage-1 adapter | §30 step 11's boundary (caught by the compiler) |

Three claims are deliberately **not** expressed as mutations, because no single line carries them, and
the script says so in its own output: *"the integration writes nothing"* (it holds no transaction
runner, so a write is not expressible — asserted by the pass-alone census test), *"the engine stays
pure"* (asserted by the engine's architecture suite), and *"the clock is read once per pass"* (asserted
structurally by the integration's architecture suite).

One rule has a fallback that is an **equivalent mutant** and is recorded as one rather than credited as
a caught mutation: the same *"an unknown exercise is its own family"* fallback inside
`exposedFamiliesOf` (the completion's side) cannot be distinguished by any test, because the **element
choice** decides which family a decision is about and refuses an element whose family it cannot name
either way. The mutation is therefore stated where it is observable — in `PresentedElement.familyOf`,
which is what turns *"we do not know this exercise"* into *"here is a family"*. A mutation that no test
can distinguish proves nothing, and pretending it does would be a row that lies.

## 13. Claim → test

| claim | suite |
| --- | --- |
| the target is the next future opportunity, never the completed or a past one | `ProgramAdaptiveIntegrationTest.theDecisionsTargetIsNeverAPastOpportunityOfTheSameRevision` |
| the temporal boundary is exclusive on the decision's own day | `AdaptiveTargetSelectionTest.anOpportunityPlannedForTheDecisionsOwnDayOrEarlierIsNotAFutureOpportunity` |
| a past `MISSED` opportunity is startable and still never the target | `AdaptiveTargetSelectionTest.aPastStartableOpportunityIsNeverTheTarget` |
| no future opportunity writes nothing at all | `ProgramAdaptiveIntegrationTest.aRevisionWithNoFutureOpportunityProducesNothingAtAll` |
| a completion never adapts another revision | `ProgramAdaptiveIntegrationTest.aCompletionNeverAdaptsAnotherRevisionsOpportunity` |
| a new revision starts its own baseline | `ProgramAdaptiveIntegrationTest.aNewRevisionStartsItsOwnAdaptiveBaselineAndDoesNotCarryTheOldOneOver` |
| the three legs are stored as one fact | `ProgramAdaptiveIntegrationTest.aCompletedSessionAdaptsTheNextFutureOpportunityAndStoresAllThreeLegs` |
| a guard refusal is kept, with its reason and no adjustment | `ProgramAdaptiveIntegrationTest.aGuardRefusalIsStoredWithItsDecisionAndItsReasonAndNoAdjustment` |
| the reason survives a restart and is never re-derived | `ProgramAdaptiveIntegrationTest.theDecisionReasonSurvivesARestartAndIsNeverReDerived` |
| supersession is by reference, and the old row is untouched | `ProgramAdaptiveIntegrationTest.aSecondDecisionSupersedesTheFirstByReferenceAndNeverRewritesIt` |
| the future opportunity consumes the adjustment into an immutable snapshot | `ProgramAdaptiveIntegrationTest.theFutureOpportunityConsumesTheAdjustmentIntoAnImmutableSnapshot` |
| a held window advances the bookkeeping and writes no decision | `ProgramAdaptiveIntegrationTest.aHeldWindowAdvancesTheBookkeepingAndWritesNoDecision` |
| the bookkeeping survives a restart and confirms the next window | `ProgramAdaptiveIntegrationTest.theWindowBookkeepingSurvivesARestartAndConfirmsTheNextWindow` |
| a user-authored or pinned element is never adapted | `ProgramAdaptiveIntegrationTest.anElementTheUserAuthoredIsNeverAdapted`, `…aPinnedElementIsNeverAdapted` |
| a `MANUAL` revision is refused before any element is considered | `ProgramAdaptiveIntegrationTest.aManualRevisionIsRefusedBeforeAnyElementIsConsidered` |
| no ladder, and no classification, are typed gaps | `…noDeclaredProgressionIsATypedNoDecision`, `…noFamilyClassificationIsATypedNoDecision` |
| a past or same-day opportunity cannot receive the decision, at the runtime's own boundary | `SessionRuntimeTest.aDecisionAboutAPastOrSameDayOpportunityIsRefusedAndNothingIsCompleted` |
| the producer and the consumer of §4's rule share one calendar | `ProgramAdaptiveIntegrationArchitectureTest.theCompositionRootHandsTheProducerAndTheConsumerOneCalendar` |
| an unconfirmed prescription exposes nothing | `…anUnconfirmedPrescriptionExposesNoFamilyAtAll` |
| the changed element belongs to the target's own plan day | `…theChangedElementBelongsToTheTargetOpportunitysOwnPlanDay` |
| a failure at any leg rolls all four back | `…aFailureAtAnyLegOfTheCompletionLeavesAllFourLegsUnchanged` |
| the Stage-1 adaptive tables are never touched | `…theFlowNeverTouchesTheStageOneAdaptiveTables` |
| the pass alone writes nothing | `…theAdaptivePassAloneWritesNothingAtAll` |
| the target rule, element choice and judgements, purely | `AdaptiveTargetSelectionTest` (15 tests) |
| the dependency direction, the vocabulary shapes and the legacy boundary | `ProgramAdaptiveIntegrationArchitectureTest` (8 tests) |
| the future-opportunity contract, at the runtime's own boundary | `SessionRuntimeTest` (six tests over `AdaptiveTargetRefusal`) |
| the stored reason round-trips, and an unknown token is refused | `AdaptiveMapperTest.theDecisionReasonRoundsTripsAndAnUnknownTokenIsRefused` |
| the schema appends exactly these columns, and upgrades a populated database without inventing a count | `ProgramSchemaTest.theAdaptiveWindowBookkeepingIsAppendedToTheFamilyStateTableAndInventsNoCount`, `…SurvivesTheChainAndReconstructsTheDomainState` |

## 14. Deliberate changes to earlier stages

Recorded rather than hidden, because each one is a contract another stage owns:

1. **§30 step 8's completion seam** (`AdaptiveCompletion`) gained the *adaptive state* leg and the
   `WindowEvaluated` shape, and its decision validation changed from same-slot to future-slot (§2, §3).
   `SessionRuntimeTest`'s same-slot test was replaced by six tests over the new invariant;
2. **§30 step 11's result surface** gained `verdict` (`ProgramWindowVerdict`): the policy's per-window
   answers are what the caller's confirmation counts, cooldown position and recovery exit count advance
   by, and no reason token expresses them (`PROGRESSION_COOLDOWN` deliberately does not say which
   direction it holds). The policy's own `evaluate` computes it, so the engine reports rather than
   recomputes;
3. **§30 step 11's decision record** (`AdaptiveDecision`) gained `reason`, and §23's decision table gained
   the column it is stored in (§5);
4. **`WorkoutSlot` gained `isStartable`**, so the target rule and the runtime's start path state "which
   opportunities are ahead" once;
5. **`SessionRuntimeArchitectureTest`'s fence was revised, not relaxed**: `FamilyProgressionState` now
   crosses the runtime as §27's state leg, and the fence was *tightened* where the new stage could leak
   into it (no integration, no window, no ladder, no classification, no reason vocabulary — with the
   positive half of the rule asserted beside it);
6. **`SessionRuntime` holds a `ZoneId`**, and it is a required argument. *"a value it is given, never one
   it acquires"* is the same line the `Clock` is on: the runtime still computes no date, chooses no date,
   opens no window and does no scheduling (§20, §25) — it reads one calendar for **one** comparison, the
   day an adaptive decision was taken on against the day of the opportunity that decision names, because
   a date and an instant are different facts and §26 puts the conversion in the layer that owns the clock.
   The composition root hands the *same value* to the integration, so the producer's choice and the
   consumer's check are one rule; the architecture suite asserts both halves (the scheduling vocabulary is
   still absent, `LocalDate.ofInstant(` appears once, `ZoneId.systemDefault()` appears nowhere in the
   runtime, and the container passes `zone = zone` to each side).

**One correction inside this change.** The first version of the consumer check used the *slot's* status
alone; a `MISSED` opportunity is startable, so a decision about a past day was accepted by the runtime
while the producer could no longer have chosen it. The temporal clause is now checked on both sides, and
the boundary test asserts it on the consumer side for an opportunity with **no attempt** and
`isStartable == true` — the exact case status alone cannot decide.
