# Program System — the Scheduler (PR 7)

Scope: the **use-case and domain layer** that turns a saved Program's current revision into planned
opportunities — deterministic slot generation, fixed-weekday and flexible-frequency scheduling, the
planning horizon and its extension, missed-slot detection, pause interaction, and the supersession of
exactly the future slots a revision change invalidates.

Reference architecture: `docs/Monk Fitness — Program System Implementation Blueprint.MD` (cited below as
**§N**). Implementation order: §30 step 7, *Scheduler*. Companions: `docs/PROGRAM_DOMAIN_FOUNDATION.md`
(PR 1), `docs/PROGRAM_ROOM_SCHEMA.md` (PR 2), `docs/PROGRAM_SCHEDULE_FREQUENCY_CORRECTION.md` (PR 2.1),
`docs/PROGRAM_DATA_ACCESS.md` (PR 3), `docs/PROGRAM_COMPOSITION_ROOT.md` (PR 4), `docs/PROGRAM_LIFECYCLE.md`
(PR 5), `docs/PROGRAM_MANUAL_EDITOR.md` (PR 6).

This change is **timing**. No session runtime, no adaptive policy, no generator, no progress
calculation, no import/export, no legacy removal and no UI wiring — §30 steps 8+ own those. It adds no
entity, no DAO, no mapper and no repository method: the schedule's persistence (PR 3) already stores
slots, and the only two operations this stage needs — store the slots a caller decided on, record an
outcome — are the two it already had.

---

## 1. The graph

```text
AppContainer                                     ← PR 4, unchanged apart from one construction
    ↓
    ProgramScheduler                             ← this PR (§30 step 7)
        ├── ProgramRepository                    ┐ read-only here: the lifecycle, the archive stamp
        ├── ProgramPlanRepository                │ and the two start dates; the plan is READ, never
        ├── ProgramScheduleRepository            ┘ written — the only rows this stage writes
        ├── clock: Clock                         ← the day the pass is made on (§26)
        ├── idGenerator: IdGenerator             ← the identity of every opportunity it adds (§26)
        ├── zone: ZoneId                         ← the calendar a date is read in
        └── inTransaction                        ← one unit per pass
    ↓
    SlotPlanner              (pure domain)       ← the decision: a request in, a plan out, no I/O
    SlotPlan / ScheduleRequest (pure domain)     ← what was decided, and what it was decided from
    ScheduleHorizon / ScheduleCalendar           ← the horizon and the date arithmetic
    ProgramSchedulingResult  (pure domain)       ← §28's classes, the refusals and the outcome
    ↓
ViewModels (not wired by this PR)
```

| file | layer | responsibility |
| --- | --- | --- |
| `domain/program/ScheduleHorizon.kt` | pure domain | §20's `PLANNING_HORIZON_DAYS = 30` and the inclusive `ScheduleWindow` a pass covers |
| `domain/program/ScheduleCalendar.kt` | pure domain | the date arithmetic: which weekdays a schedule trains on (fixed and flexible), the deterministic flexible spread, and a pause expressed in dates |
| `domain/program/SlotPlan.kt` | pure domain | the request (`ScheduleRequest`) and the decision (`SlotPlan`): what to create, what to supersede and why, what is missed |
| `domain/program/SlotPlanner.kt` | pure domain | the decision itself — `plan(request)`, a function of its request alone |
| `domain/program/ProgramSchedulingResult.kt` | pure domain | §28's `Success / Refused / Failure`, the typed refusals, and `ScheduleOutcome` |
| `domain/usecase/ProgramScheduler.kt` | use case | the operation: read, decide, write. `schedule` writes; `preview` does not |
| `di/AppContainer.kt` | composition root | one construction added (+33 lines, 0 removed); nothing else |

## 2. The flow, and where each decision lives

```text
read            decide                                   write
program ──┐
revision ─┼─→  SlotPlanner.plan(ScheduleRequest)  ─→    one transaction:
slots ────┤      window → dates → create                 addSlots(create)
pauses ───┤      reconcile (supersede, miss)             recordSlotOutcome(SUPERSEDED) × n
anchor ───┘                                              recordSlotOutcome(MISSED) × n
asOf ─────┘
```

The *decision* is pure: `SlotPlanner.plan` takes a `ScheduleRequest` — the revision, two dates, the
slots the Program already has, its pause intervals as dates, and an identity source — and returns a
`SlotPlan`. No clock, no repository, no database, no session type, no state. That is what makes the
whole of §20 testable on the JVM, and what makes "the Scheduler schedules slots, never Sessions" a
property of the types rather than a rule the code has to remember.

The *operation* is what makes the decision about a **stored** Program: it loads the Program, refuses a
completed or archived one, reads the revision the Program points at, resolves the two dates the decision
needs, and stores what was decided in one transaction.

### The two dates

* **anchor** — the date the plan's `day 1` falls on. It is a fact about the Program, taken from
  `actualStartDate` when the Program has actually started and from `plannedStartDate` otherwise. A
  Program with neither is refused: the Scheduler does not invent a `day 1`, because picking today would
  be it deciding when the user's program begins (§3).
* **asOf** — the day the pass is made on, read once from the injected clock in the injected zone. It is
  the first date that can still be trained, the date a passed opportunity is measured against, and the
  origin of an indefinite horizon.

Nothing else is consulted for either. In particular the lifecycle is never read to derive an anchor, and
no code path here writes a lifecycle: **planning from a planned start date plans opportunities and starts
nothing** (§3), which the suite measures on the stored Program row.

## 3. The rules, and where each is enforced

| §20/§3/§12 rule | Enforcement |
| --- | --- |
| The Scheduler schedules **slots**, never Sessions | `SlotPlan` holds `WorkoutSlot` values; no session type appears in any file of this stage, and the class holds no session repository (asserted by `ProgramSchedulerArchitectureTest` on the constructor and on the sources) |
| It never creates a `WorkoutSession` | Same: the collaborator the write would need is absent. Measured behaviourally too — after passes, `workout_session`, the snapshot tables, `session_exercise` and `program_set_log` are all still empty |
| A Slot is an **opportunity**, not an amount of work | The only rows written are `WorkoutSlot`s, which declare no repetitions, duration, set count or score — pinned by reflection in `SlotPlannerTest.anOpportunityCarriesNoAmountOfWork` |
| No repetitions/duration/score is inferred or stored on a Slot | `ScheduleOutcome` carries the decision and the dates, and nothing measured (`ProgramSchedulerArchitectureTest.theSchedulerCannotExpressAnAmountOfWork` pins both its fields and the sources' vocabulary) |
| `MISSED` is not zero performance | Missing writes a status and nothing else: the slot keeps its date, has no completion stamp, no session, and no value is recorded for it (§12) |
| A missed Slot does **not** slide the schedule | The plan day a date presents is `ordinal(date) mod days`, where the ordinal counts *scheduled dates from the anchor* — never completions and never misses. A missed opportunity changes no other date, no other plan day and no other status |
| Existing planned dates are preserved | `create` skips every date the Program already has a slot on, whatever that slot's status; nothing existing is rewritten, re-pointed or deleted — a supersession changes a status and stops there |
| Fixed weekdays are deterministic | Membership in the user's own `Set<DayOfWeek>` |
| `FlexiblePerWeek.sessionsPerWeek` is authoritative | The count is read from the revision and spread by `flexibleSpread`; no function in this stage counts existing slots, and the suite drives a second pass over thirteen existing opportunities to prove the rhythm did not change |
| Same inputs → same slot set and order | The decision reads no clock, no random source and no mutable state; two identical requests produce equal plans, ids included |
| Deterministic tie-breaks; no random scheduling | The flexible spread is a closed-form function of the count (all seven counts pinned in the suite); there is no `Random`, no shuffle and no map iteration order anywhere in the decision |
| Repeated occurrences remain the Revision's occurrences | A created slot *names* an existing `ProgramDay` of the revision; the plan's days are cycled by ordinal and no day or exercise is ever built here (§9) |
| Paused Programs freeze active program time and missed-opportunity logic | A date covered by a pause is not a training date, so the plan does not advance through the pause (the days that would have fallen inside it fall after it); a past opportunity inside a pause is superseded rather than missed; and an open pause covers every date from its start onward, so planning stops there |
| A planned start date does not start a Program | No path writes a lifecycle column. Measured on the stored Program row for a `NOT_STARTED` Program planned from its planned start date |
| Completed/archived Programs receive no future slots | Both are refusals that write nothing at all — measured by comparing the row count of **every** table before and after |
| The Scheduler does not modify Program lifecycle | `ProgramRepository` is read for `lifecycleStatus`, `archivedAt` and the start dates; no `updateProgram` call exists in this stage |
| It does not invoke Adaptive policy/engine | No adaptive type or port appears in any file of this stage, and none is a collaborator; §20 puts Adaptive after the Focus Planner in the pipeline |
| It does not rewrite immutable Revisions | `ProgramPlanRepository` is used for `currentRevision` only; `saveNewRevision` is never called, so no revision row, day, element or pointer is written by scheduling |
| It does not perform UI decisions | The outcome is a value; no screen or ViewModel is wired (asserted by the architecture suite) |
| No `RevisionConflict` mechanism | There is none, by design: a pass works from the Program's stored current revision. A draft/save-time staleness check is a UI/editor policy, and this stage neither introduces one nor depends on one |
| Slot supersession is explicit and auditable | `SupersededSlot` carries the rule that did it (`THE_REVISION_NO_LONGER_PRESENTS_THE_DATE`, `THE_OPPORTUNITY_PASSED_WHILE_PAUSED`); nothing is deleted, and the superseded row keeps its identity, date and plan day |
| Multiple sessions may later attempt one Slot | A slot's `attempts` stay untouched by a pass; nothing in the decision is unique on a session or a slot-per-session |

## 4. The supersession rule, stated exactly

This is the rule the brief asked to be defined rather than assumed, and it is deliberately **not**
whole-schedule regeneration.

> A scheduling pass supersedes exactly those existing opportunities that are still `PLANNED` and that the
> pass can no longer present, and it leaves every other opportunity byte-identical.

Concretely, for a slot that is still `PLANNED`:

```text
plannedFor < asOf   and  a pause covers plannedFor   →  SUPERSEDED (THE_OPPORTUNITY_PASSED_WHILE_PAUSED)
plannedFor < asOf   and  no pause covers it          →  MISSED  (an expectation the user did not meet)
plannedFor ≥ asOf   and  the revision still presents the date  →  untouched
plannedFor ≥ asOf   and  the revision cannot present the date  →  SUPERSEDED (THE_REVISION_…)
```

where **the revision presents a date** when both hold:

```text
the date's weekday is one the revision's schedule trains on (§20: fixed weekdays, or the
    deterministic spread of FlexiblePerWeek)
the date lies within the revision's own run: [anchor, anchor + days - 1] for FixedDays,
    unbounded above for Indefinite
```

Four consequences worth stating, because each is a decision rather than an accident:

* **The second line is the revision's run, not the planning window.** An indefinite revision still
  presents the dates beyond today's 30-day horizon, so switching a program from fixed to indefinite
  supersedes nothing — the horizon is how far ahead the plan is *written down*, not how far it reaches.
  Shortening a program, conversely, supersedes exactly the dates beyond its new end and preserves the
  rest.
* **Only `PLANNED` opportunities are eligible.** A completed slot is history and a missed one already
  happened: `MISSED` is not `SUPERSEDED` and a later edit does not reach back and rewrite either. This is
  §20's *"*future* incompatible slots become SUPERSEDED"* read literally.
* **The plan's content is not part of presentation.** Changing what a Wednesday contains creates a new
  revision (§6) and supersedes nothing: every Wednesday is still a Wednesday. The opportunity is the
  date; what is trained on it is the revision's business, and a session snapshots the presentation it
  captured (§16, §19). A slot is therefore *not* re-pointed at the new revision — re-pointing would be a
  rewrite of an existing row.
* **The rule is idempotent.** After one pass no future slot fails the test, so a second pass supersedes
  nothing; and a pass applied to a tree it already planned creates nothing. That is what makes
  "invoke it after a Save" and "invoke it to extend the horizon" the same operation.

### One date holds one opportunity, ever

A superseded date is **not** re-planned. The date already holds a row — the record that the opportunity
was cancelled — and writing a second row for that date would be the silent overwrite §33 forbids. The
consequence is visible when a user switches a schedule away and back: the Mondays that were superseded
stay superseded, because the record of *what happened to that opportunity* is the durable fact. The
alternative (re-planning a superseded date) is discussed under *Decisions to review*.

## 5. The horizon

| revision duration | what a pass covers |
| --- | --- |
| `FixedDays(d)` | `[max(anchor, asOf), anchor + d - 1]` — the revision's own remaining run, because its end is a fact the plan can see |
| `Indefinite` | `[max(anchor, asOf), max(anchor, asOf) + 29]` — §20's *"*exactly 30 days of future planning*"*, from the first date that can still be trained |

**Extension is the same operation applied later.** An indefinite program's window is a rolling one: a
pass made a day later covers a window a day further out, adds the opportunity that now fits at the far
end, marks what passed, and preserves everything it already had. There is no separate `extendHorizon`
call to get wrong, and no state beyond the slots themselves.

A pass never plans an opportunity in the past: the window's first date is the later of the plan's anchor
and the day of the pass, so an opportunity nobody can take is never created (and never born missed).

## 6. Verification

All measured on the final bytes, with a fresh `--rerun-tasks` run whose JUnit XML `timestamp` was checked
against `date -u`:

| gate | result |
| --- | --- |
| pristine `origin/main` baseline (`9231bfc`, detached worktree, `:app:cleanTest :app:testDebugUnitTest --rerun-tasks`) | **231 classes / 1924 tests / 0 failures / 0 errors / 0 skipped** |
| branch, same command | **234 classes / 1989 tests / 0 failures / 0 errors / 0 skipped** — **+3 classes / +65 tests** |
| freshness | newest XML `timestamp` `2026-09-19T07:00:23Z` vs `date -u` `07:00:49Z`; `BUILD SUCCESSFUL in 39s` |
| structural cross-check | `grep -rl '@Test' app/src/test/java \| wc -l` = 234; `grep -rho '@Test' … \| wc -l` = 1989 |
| focused PR-7 suites | `ProgramSchedulerTest` 32, `SlotPlannerTest` 24, `ProgramSchedulerArchitectureTest` 9 — 65 tests, 0 failures |
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `:app:compileReleaseKotlin` | BUILD SUCCESSFUL |
| `:app:assembleDebug` | BUILD SUCCESSFUL |

The +65 tests are exactly the three new classes. One pre-existing test class was edited (the revised
composition-root guard, below) and no pre-existing test was deleted, loosened or skipped.

`lintVitalRelease` remains excluded for the known pre-existing `themes.xml` resource cycle; the release
evidence is `compileReleaseKotlin` plus the assembled debug APK.

### One pre-existing guard was revised, not relaxed

`CompositionRootArchitectureTest.theCompositionRootDecidesNothing` (PR 4) forbids a decision in the
wiring by token, and its list contained `"Scheduler"` — correct when no stage could wire one, and exactly
wrong now: §30 step 7's whole point is that the container *constructs* the Scheduler. The token was
replaced by the vocabulary of the decision the container must not contain (`SlotPlanner`, `SlotPlan`,
`ScheduleRequest`, `ScheduleWindow`, `PausedInterval`, `SlotIdSource`, `PLANNING_HORIZON_DAYS`,
`plannedFor`, `asOf`, `DayOfWeek`, `superseded`) and by a **stricter** structural pin: the container names
the Scheduler in exactly three places (import, property, construction), constructs it exactly once, and
the construction is asserted against the exact literal that lists the six documented collaborators. The
guard now says what it always meant — the container may hold the node, never the decision — and it says
it about the stage that made the distinction load-bearing.

### The cases the brief names

| case | where it is measured |
| --- | --- |
| `FixedWeekdays` | `theFirstPassPlansTheRevisionOnItsOwnDaysAndNamesItsOwnPlanDays`, `SlotPlannerTest.aFixedWeeklyRevisionIsPlannedOnTheDaysTheUserNamedAndOnNoOthers` |
| `FlexiblePerWeek(1..7)` | `aFlexibleFrequencyIsPlannedFromTheStatedCountAndNotFromTheSlotsItAlreadyHas` (all seven counts, each with a second pass over existing slots), `theFlexibleSpreadIsPinnedForEverySupportedFrequency` |
| finite duration | `aFixedRevisionIsPlannedToItsOwnEndAndNotToAHorizon`, `shrinkingARevisionSupersedesOnlyTheDatesBeyondItsNewEnd`, `anElapsedRevisionCreatesNothingAndStillReconcilesWhatItFinds` |
| indefinite horizon | `anIndefiniteHorizonIsExactlyThirtyDaysAndExtendsWhenThePassIsMadeLater` |
| first scheduling window | `thePlanStartsAtTheFirstScheduledDateOnOrAfterTheAnchor`, `anAnchorInTheFutureIsNotPlannedBackwards`, `planningFromAPlannedStartDatePlansOpportunitiesAndStartsNothing` |
| extending an indefinite horizon | `anIndefiniteHorizonIsExactlyThirtyDaysAndExtendsWhenThePassIsMadeLater` |
| a future missed opportunity | `anOpportunityBehindTheUserIsMissedAndTodaysIsNot` (today's and tomorrow's stay open) |
| paused interval | `aPausedDateIsNotAPlanningDateAndThePlanResumesWhereItFroze`, `anOpportunityThatPassedInsideAPauseIsSupersededRatherThanMissed`, `aPauseDoesNotDestroyAnOpportunityThatIsStillAhead`, `anOpenPauseFreezesBothPlanningAndMissedDetection` |
| already-completed Program | `aCompletedProgramIsRefusedAndNothingIsWrittenAtAll` |
| archived Program | `anArchivedProgramIsRefusedAndItsOpportunitiesAreKept` |
| revision change with future slots | `aRevisionThatMovesTheScheduleSupersedesExactlyTheDatesItNoLongerPresents` |
| preserving unaffected future slots | `aRevisionThatOnlyAddsAWeekdayKeepsEveryOpportunityItStillPresents`, `aRevisionThatChangesOnlyThePlansContentSupersedesNothingAndCreatesNothing` |
| deterministic regeneration of the same horizon | `theSameRequestAlwaysProducesTheSameDatesAndTheSamePlanDays`, `theSamePassAppliedTwiceAddsNothingAndRewritesNothing` |
| no duplicate slots when applied twice | `theSamePassAppliedTwiceAddsNothingAndRewritesNothing`, `aDateThatAlreadyHoldsAnOpportunityIsNeverPlannedAgainEvenAfterItWasSuperseded` |
| no Session creation | `aPassCreatesNoSessionSnapshotOccurrenceOrSet` |
| no mutation of `ProgramRevision` | `aPassWritesNothingButSlotsAndNeverTouchesThePlan` (the whole revision value is re-read and compared), `shrinkingARevisionSupersedesOnlyTheDatesBeyondItsNewEnd` |
| no sliding of all future dates after a miss | `aMissedOpportunityDoesNotSlideTheRestOfTheSchedule`, `SlotPlannerTest.aMissedOpportunityMovesNoOtherDateAndNoOtherPlanDay` |
| transaction / error propagation | `onePassIsOneUnitSoAFailureLeavesTheScheduleExactlyAsItWas` (a planted DAO fault: the whole pass rolls back and the failure is propagated, not absorbed) |

## 7. RED evidence

`scripts/program-scheduler-red-mutations.sh` applies one mutation at a time to the *production* sources,
reruns the focused scheduler suites, restores the file and proves the restoration by `md5sum -c`. Fifteen
rules plus a control, **16 caught / 0 missed** on the final bytes.

| mutation | rule it breaks |
| --- | --- |
| every later date is shifted by the number of misses | no whole-schedule sliding after a miss |
| the flexible rhythm is picked at random | deterministic generation |
| the flexible frequency is derived from the slots that exist | the stated frequency is authoritative |
| the Scheduler acquires a session repository | §33: never let the Scheduler create Session |
| an occupied date is planned a second time | one date holds one opportunity; no overwrite |
| paused dates are planned as if the program were running | pause freezes active program time |
| missed detection ignores pauses | pause freezes missed-opportunity logic |
| today's opportunity is already missed | missed is measured against the pass date |
| every future opportunity is superseded | supersession is precise, not regeneration |
| the indefinite horizon is 14 days | §20: exactly 30 days of future planning |
| every date presents the first plan day | the plan's own days cycle in order |
| a pass mints a revision | §6: revisions are immutable |
| a completed Program is planned | completed Programs receive no future slots |
| an archived Program is planned | archive stops future planning (§29) |
| planning starts the Program | a planned start date does not start a Program |

The highest-risk mutations, with the assertion text they produced (a mutation is only evidence if the
failure names what it measured):

| mutation | failing tests | measured |
| --- | --- | --- |
| dates shifted by the number of misses | 1 | `aMissedOpportunityMovesNoOtherDateAndNoOtherPlanDay`: *"a miss does not slide the schedule: the dates a pass would add are the same dates, in the same order…"* |
| frequency derived from the slots that exist | 2 | `aFlexibleFrequencyIsPlannedFromTheStatedCountAndNotFromTheSlotsItAlreadyHas`: *"…the count comes from the revision and never from the rows the last pass wrote expected:\<0\> but was:\<17\>"*, and the pure suite's two-per-week date list |
| paused dates planned anyway | 3 | `aPausedDateIsNotAPlanningDateAndThePlanResumesWhereItFroze`: *"…pausing freezes active program time, so the days that would have fallen inside the pause fall after it instead (§3)"* |
| every future opportunity superseded | 20 | `anOpportunityThatPassedIsMissedAndNoWorkIsInventedForIt`: *"the status is recorded on that slot and nowhere else: the other eight opportunities are still open… expected:\<8\> but was:\<0\>"*; `anIndefiniteHorizonIsExactlyThirtyDaysAndExtendsWhenThePassIsMadeLater`: *"the opportunities it already had are untouched: same identity, same date, same plan day"* |

One claim is expressed structurally rather than behaviourally, and the script says so out loud: **the
Scheduler creates no Session**. The mutation gives the Scheduler the session repository it would need, and
the architecture suite fails on the collaborator list — a Scheduler with no session runtime cannot write
one, which is exactly why the guarantee is the absence of the dependency (plus a whole-database row
census after real passes) rather than a mutated session write.


## 8. Decisions to review

1. **A superseded date is not re-planned.** The strict reading chosen here — one date holds one
   opportunity, ever — makes "no duplicate slots", "no overwrite" and "supersession is history" all
   structural, at the cost that a schedule switched away and back leaves the dates it vacated
   superseded. The alternative is to allow a second (new) slot for a date whose only slot is
   `SUPERSEDED`, which restores the opportunities but makes one date able to hold two rows and gives
   every consumer of "the slot of a date" a choice to make. Both readings are one condition in
   `SlotPlanner`; the owner should pick one before §30 step 8 (session runtime) resolves a date to a
   slot.
2. **A pause does not supersede an opportunity that is still ahead.** §20's slot-state prose names *a
   pause* among the reasons a slot is superseded, while §3 says a pause *freezes* active program time
   and missed-opportunity logic. This stage implements the freeze reading for the future (an
   opportunity inside a still-open or already-closed pause interval is left open, and the pause only
   excludes the date from *planning*) and the supersession reading for the past (an opportunity that
   passed inside a pause is superseded rather than missed, because the user was not expected to train
   it and it cannot be taken any more). Superseding the future ones instead would destroy opportunities
   a one-day pause could otherwise leave intact, with no way back — the plan never re-plans a date.
3. **A pause covers whole days.** A pause stored as instants is read as covering the date it starts on
   and the date it ends on, even a 21:00 pause or a 09:00 resume. A half-covered day would have to
   answer "was 14:00 still an opportunity?", which is a question about the user's intention rather than
   about the plan.
4. **A slot that survives a revision change is not re-pointed at the new revision.** It keeps the
   `revisionId` and the plan day it was scheduled from — the domain says a slot names *the revision it
   was scheduled from*, and re-pointing is a rewrite of an existing row. The effective presentation of a
   slot is the current revision plus adjustments (§16), which a session snapshots when it starts.
5. **A completed/archived Program is refused entirely rather than reconciled.** §29 gives archiving the
   effect of stopping future planning; it says nothing about the unfilled opportunities already in the
   past. This stage writes nothing at all for such a Program, so declaring an opportunity missed *after*
   the program was archived is a separate decision the owner may want (§29's "retains all history" reads
   either way).
6. **Missing is date-based and attempt-agnostic.** An opportunity whose date passed while the program was
   not paused becomes `MISSED` whether or not a session was ever started for it; the slot's `attempts`
   and the session's own record are untouched, and a session that is still `IN_PROGRESS` may later
   complete the slot (§19's complete-workout transaction writes the outcome). Exempting slots with
   attempts would let an abandoned attempt keep an opportunity open forever.
7. **Planning is refused, not deferred, without an anchor.** A Program with neither an actual nor a
   planned start is refused (`NoSchedulingAnchor`) rather than planned from today. The alternative —
   seeding an anchor — is the Scheduler deciding when a user's program begins.
8. **`preview` mints the identities it reports.** A decided-but-unstored slot has to name itself to be
   reportable, so a preview consumes ids from the injected generator while writing nothing. If a caller
   needs previews that do not consume identity, that is a small change to the outcome shape.
9. **The 30-day horizon is planned for finite programs too, but its window is its own run.** A
   `FixedDays(3650)` revision is planned in one pass of ~1500 slots. The alternative (a horizon for
   every duration, extending forever) contradicts §20's sentence, which scopes the 30 days to indefinite
   programs; a cap for very long fixed programs is a product decision.
10. **The split between `ScheduleCalendar` (arithmetic) and `SlotPlanner` (decision)** is deployment
    convenience, not architecture: both are pure domain. If a later stage wants a different spread rule
    or a different week anchor, it is one function in `ScheduleCalendar`.

## 9. Base

This branch is based on `origin/main` at `9231bfc`, the merge commit of PR 6
(`feat/program-manual-editor`), so the editor, the lifecycle, the composition root, the repositories and
the schema it builds on are all already in `main`. No rebase was needed; the diff is exactly this stage's
work plus the one additive construction in the composition root (`+33 / -0`).
