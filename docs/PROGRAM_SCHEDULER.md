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

### The third entry point (§30 step 13)

`initialSlotsFor(program, revision)` decides the opportunities a Program **being created** receives, and it
has exactly the two callers §27's creation unit names: the import (`ProgramImportService.save`, §30 step
13) and the editor's production Save for Create/Copy (`ProgramSaveService.save`, the creation
remediation). Each of them composes it the same way — decide the slots first, then write the whole unit
through `ProgramRepository.createProgram` in one transaction — and §27 requires `Create / Copy / Import →
Program + Revision + initial Slots` to be one atomic operation in every case. A creation cannot go
through `schedule(programId)` — that entry point plans a
*stored* Program (it reads the Program row, its slots and its pauses), and the whole point of the creation
unit is that nothing is stored until everything can be. So the inputs arrive as values instead of being read
and everything else is unchanged:

```text
same decision        SlotPlanner.plan, through one private `decide` both entry points call
same anchor rule     actualStartDate ?: plannedStartDate, else NoSchedulingAnchor — this entry point
                     cannot be used to make the Scheduler invent a date either
no new semantics     a Program being created has no slots and no pause intervals: empty is a fact
no transaction       it decides and writes nothing; §27's owner (ProgramRepository.createProgram) writes
```

Deciding and persisting are separated there on purpose: the caller that owns the creation unit is the caller
that writes, which is what keeps *"the Scheduler owns timing/opportunities"* true while each creation path
owns *"what Program is being created"* — the importer (see `docs/PROGRAM_IMPORT_EXPORT.md` §13) and the
Save orchestration (see `docs/PROGRAM_MANUAL_EDITOR.md`). The second half of §27's Save line is composed
the same way: `ProgramSaveService` runs one `schedule(programId)` pass after an edit's revision is stored,
inside the save's transaction, so *`Save Editor → new Revision + future-slot reconciliation`* is a
production behaviour rather than a documented intention — the pass itself, including its idempotence and
its refusal to touch completed attempts, remains entirely the Scheduler's.

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
| Same inputs → same slot set and order | The decision reads no clock, no random source and no mutable state; two identical requests produce equal plans, ids included — and the **plan day a date presents is a function of the calendar**, not of when the pass was made (see *the audit follow-up* below) |
| Deterministic tie-breaks; no random scheduling | The flexible spread is a closed-form function of the count (all seven counts pinned in the suite); there is no `Random`, no shuffle and no map iteration order anywhere in the decision |
| Repeated occurrences remain the Revision's occurrences | A created slot *names* an existing `ProgramDay` of the revision; the plan's days are cycled by ordinal and no day or exercise is ever built here (§9) |
| Paused Programs freeze active program time and missed-opportunity logic | A pause **suppresses creation**: no opportunity is created on a date it covers, and an open pause covers every date from its start onward, so planning stops there. It **freezes missed detection**: a past opportunity inside a pause is superseded rather than missed. And it **renumbers nothing**: the paused dates keep their place in the plan's sequence, so the plan day a date presents is the calendar's and never depends on the pause (§3 freezes active program *time* and missed-opportunity logic, not the plan's dates — see *the audit follow-up*) |
| A planned start date does not start a Program | No path writes a lifecycle column. Measured on the stored Program row for a `NOT_STARTED` Program planned from its planned start date |
| Completed/archived Programs receive no future slots | Both are refusals that write nothing at all — measured by comparing the row count of **every** table before and after |
| The Scheduler does not modify Program lifecycle | `ProgramRepository` is read for `lifecycleStatus`, `archivedAt` and the start dates; no `updateProgram` call exists in this stage |
| It does not invoke Adaptive policy/engine | No adaptive type or port appears in any file of this stage, and none is a collaborator; §20 puts Adaptive after the Focus Planner in the pipeline |
| It does not rewrite immutable Revisions | `ProgramPlanRepository` is used for `currentRevision` only; `saveNewRevision` is never called, so no revision row, day, element or pointer is written by scheduling |
| It does not perform UI decisions | The outcome is a value; no screen or ViewModel is wired (asserted by the architecture suite) |
| No `RevisionConflict` mechanism | There is none, by design: a pass works from the Program's stored current revision. A draft/save-time staleness check is a UI/editor policy, and this stage neither introduces one nor depends on one |
| **Three settled semantics are locked as invariants** | a superseded date is never re-planned, a pause preserves the opportunities inside it and renumbers nothing, and `FixedDays` is calendar duration — each with a test named after the rule and a RED mutation that breaks it (§5) |
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
alternative (re-planning a superseded date) is the rejected reading recorded as settled semantics in
§5.

## 5. Settled semantics (locked)

Three rules this stage implements were recorded, in earlier revisions of this document, as open decisions.
They are **settled semantics** now: each is an invariant of the Scheduler, each has a test *named after the
rule*, and each has a RED mutation that fails the suite when the rule is broken. Nothing about the
mechanics changed to lock them — they were already implemented; what changed is that they are stated as
invariants rather than as choices.

### 5.1 A superseded date is never re-planned

**The rule.** One date holds one opportunity, ever. When a pass supersedes a future opportunity — because
the revision in front of it no longer presents that date — the date keeps its superseded row and no later
pass plans it again, however many passes run, however the revision changes afterwards, and even if the
schedule that vacated it comes back.

**Why.** The row that records *what happened to an opportunity* is a durable fact about the plan, not a
cache to be refilled. Re-planning the date would make one date hold two rows — one cancelled and one open
— which would break the "no duplicate slots" and "no silent overwrite" guarantees, and would leave every
consumer that resolves *the* slot of a date with a choice to make (the session runtime of §30 step 8 is
the first such consumer). The cost is stated plainly: switching a schedule away and back leaves the
vacated dates superseded.

**Where it lives.** `SlotPlanner.plan`'s creation condition skips any date the Program already has a slot
on, whatever that slot's status.

**Pinned by.** `SlotPlannerTest.aSupersededDateIsNeverReplanned`,
`SlotPlannerTest.aDateThatAlreadyHoldsAnOpportunityIsNeverPlannedAgain`,
`ProgramSchedulerTest.aDateThatAlreadyHoldsAnOpportunityIsNeverPlannedAgainEvenAfterItWasSuperseded`
(which switches a schedule away, back, and measures that nothing opens).

**Regression caught by.** `an-occupied-date-is-planned-again` — dropping the occupancy guard makes the
suite plan a second opportunity for a date that already holds one.

### 5.2 A pause preserves the opportunities inside it, and renumbers nothing

**The rule.** A pause interval does exactly two things to the plan: no opportunity is *created* on a date
it covers, and no opportunity that *passed* inside it is counted as missed. It does **not** supersede or
move the opportunities inside it, and it does **not** renumber anything: a paused date keeps its place in
the plan's sequence, so the plan day a date presents is the calendar's and never depends on the pause.

**Why.** §3 says a pause freezes *"active program time and missed-opportunity logic"* — measurement and
missing — while §20 forbids sliding a schedule. §20's slot-state prose does name *a pause* among the causes
of `SUPERSEDED`, and this stage deliberately reads that sentence narrowly, because the wide reading is
unimplementable within the constraints the blueprint itself sets: superseding the opportunities inside a
pause would destroy opportunities a one-day pause could leave intact, and the plan never re-plans a date
(§5.1), so they could never come back; and renumbering the dates after a pause would split the plan into
two cycles, contradicting the slots that are already persisted on either side of the interval and making a
date's plan day depend on *when* the pass was made. The narrow reading is the one that keeps every
constraint: an opportunity inside a still-open or already-closed pause interval is left exactly as it is,
while a date it covers is not a date the Scheduler *plans* on.

**Where it lives.** `SlotPlanner.planDates` never consults the pauses (that is the calendar lock), while
`SlotPlanner.plan`'s creation filter and reported `scheduledDates` exclude the dates a pause covers, and
`program_workout_slot` rows are only ever re-statused, never deleted.

**Pinned by.** `SlotPlannerTest.aPausePreservesFutureOpportunitiesAndRenumbersNothing` (both halves in one
place: preserved opportunities and cycle continuity across the interval),
`SlotPlannerTest.aPauseDoesNotRenumberThePlanForTheDatesThatFollowIt`,
`SlotPlannerTest.aSinglePausedDateDoesNotCollideTwoConsecutiveDatesOntoTheSamePlanDay`,
`SlotPlannerTest.thePlanDayOfADateDoesNotDependOnWhetherTheProgramWasPaused`,
`SlotPlannerTest.anOpportunityAheadOfTheUserInsideAPauseIsLeftOpen`,
`ProgramSchedulerTest.aPauseDoesNotDestroyAnOpportunityThatIsStillAhead`,
`ProgramSchedulerTest.aPauseAddedAfterTheScheduleWasPlannedDoesNotRenumberTheRemainingDates`,
`ProgramSchedulerTest.theSameDatesPresentTheSamePlanDaysWhetherOrNotTheProgramWasPaused`.

**Regression caught by.** `the-plan-is-counted-along-a-pause-aware-walk` (the defect the audit found:
counting the plan's days along a walk that skips paused dates) and
`a-pause-supersedes-the-opportunities-it-covers` (superseding what a pause covers instead of preserving
it).

### 5.3 `FixedDays` is calendar duration, not active duration

**The rule.** `FixedDays(d)` runs `[anchor, anchor + d - 1]` — `d` **calendar** days from the plan's
anchor — whether or not the program was paused inside that window. A pause does not extend the run, does
not move its end and does not add dates to it.

**Why**, in the order the evidence is strongest:

* `ProgramDuration.FixedDays` is documented in the frozen domain foundation as *"a program that runs for a
  known number of **calendar days**"* — the type's own contract;
* `ProgramDuration`'s KDoc reads the two duration forms as progress vocabularies (a fixed program has a
  total, `Active 12 of 30 days`; an indefinite one has none), which is a statement about elapsed calendar
  days;
* §20 forbids sliding a schedule, and an active-days run is a run whose dates move whenever a pause
  happens; §27's atomicity list has no "pause extends the plan" operation either;
* and an active-days run cannot be reconciled with immutable slots: the dates after a pause would have to
  be re-planned onto other plan days, which is whole-schedule sliding.

**Where it lives.** `SlotPlanner.plan`'s `windowEnd` for `FixedDays`, which reads the anchor and the
duration and nothing else.

**Pinned by.** `SlotPlannerTest.aFixedRunEndsOnItsCalendarEndHoweverLongTheProgramWasPaused` (the window)
and `ProgramSchedulerTest.aFixedRunEndsOnItsCalendarEndAndNotOnTheDateAPauseWouldPushItTo` (end to end
through persistence: a fourteen-day run from 2026-09-14 ends on 2026-09-27, where counting active days
would end it on 2026-10-26 and plan four more opportunities).

**Regression caught by.** `a-fixed-run-is-extended-by-the-paused-days`.

## 6. The horizon

| revision duration | what a pass covers |
| --- | --- |
| `FixedDays(d)` | `[max(anchor, asOf), anchor + d - 1]` — the revision's own remaining run, because its end is a fact the plan can see |
| `Indefinite` | `[max(anchor, asOf), max(anchor, asOf) + 29]` — §20's *"*exactly 30 days of future planning*"*, from the first date that can still be trained |

### A fixed run is a number of *calendar* days, and a pause never renumbers the plan

Two readings of §3's *"Pause freezes active program time"* are possible: that a `FixedDays(d)` revision
runs `d` **calendar** days from its anchor (so a pause inside it costs the user nothing but does not move
the run's end), or that it runs `d` **active** days (so a pause inside it extends the run). The evidence
in the repository settles it for the first reading, and this stage implements that:

* `ProgramDuration.FixedDays` is documented, in the domain foundation, as *"a program that runs for a
  known number of **calendar days**"* — the type's own contract, and the frozen foundation outranks a
  reading of a prose sentence;
* `ProgramDuration`'s own KDoc reads the two forms as progress vocabularies — a fixed program has a total
  (`Active 12 of 30 days`), an indefinite one has none — which is a statement about elapsed calendar days;
* §20 forbids sliding a schedule, and an "active days" run is a schedule that moves whenever a pause
  happens; §27's atomicity list has no "pause extends the plan" operation either;
* and an active-days run cannot be made consistent with immutable slots: the dates after a pause would
  have to be re-planned onto other plan days, which is whole-schedule sliding — the thing the brief
  forbids.

So: **`FixedDays(d)` = `[anchor, anchor + d - 1]`, pause or no pause** (`aFixedRunEndsOnItsCalendarEndHoweverLongTheProgramWasPaused`),
and **a paused date keeps its place in the plan's sequence** — it is not a date an opportunity is created
on, and it is not a date the plan skips.

**Extension is the same operation applied later.** An indefinite program's window is a rolling one: a
pass made a day later covers a window a day further out, adds the opportunity that now fits at the far
end, marks what passed, and preserves everything it already had. There is no separate `extendHorizon`
call to get wrong, and no state beyond the slots themselves.

A pass never plans an opportunity in the past: the window's first date is the later of the plan's anchor
and the day of the pass, so an opportunity nobody can take is never created (and never born missed).

### The audit follow-up (post-review): the pause/ordinal interaction

The first version of this stage counted a date's plan ordinal along a walk that **excluded** the dates a
pause covers (`planDates(..., pauses)`), on the reading that a paused date is not a program date at all.
That reading is wrong (see above) and it produced a real inconsistency, found by auditing exactly the case
the review named: slots persisted *before* a pause, then a pass made *after* it.

```text
Program: MANUAL, FixedWeekdays(MON, WED, FRI), Indefinite, anchor 2026-09-14 (Mon), 3-day plan
Pass 1 at 2026-09-14        → 13 slots, 2026-09-14 … 2026-10-12, plan days cycling d1,d2,d3
Pause added                → 2026-09-16 … 2026-09-25  (5 scheduled dates)
Pass 2 at 2026-09-16        → window [2026-09-16, 2026-10-15]
```

| symptom | measured before the fix |
| --- | --- |
| the plan day of a date depends on *when* the pass was made | 2026-09-28 was `day-1` in a never-paused program and `day-2` after the pause — a 5-date shift, the count of paused scheduled dates |
| the new slots contradict the persisted ones | 2026-10-12 (persisted) is `day-1`; the new 2026-10-14 was `day-3` — `day-2` skipped entirely |
| a one-date pause collides the cycle with itself | 2026-10-12 `day-1` persisted, 2026-10-14 created as `day-1` too: two consecutive planned dates presenting the same plan day, two days of the cycle lost |
| a slot open while its date is treated as removed | the slots inside the pause stayed `PLANNED` (frozen, by design) while the walk had removed their dates from the plan they belong to |

**The fix is three lines, and it is confined to the ordinal's source.** `planDates` lost its `pauses`
parameter; the pause filter moved to the two places that need it — the reported `scheduledDates` and the
creation condition in `plan`. Creation dates, supersession, missed detection, the horizon and every
existing expectation are unchanged; the only thing that changed is that the plan's days are now counted
along the calendar. The suite gained six tests for the invariant (`aPauseDoesNotRenumberThePlanForTheDatesThatFollowIt`,
`aSinglePausedDateDoesNotCollideTwoConsecutiveDatesOntoTheSamePlanDay`,
`thePlanDayOfADateDoesNotDependOnWhetherTheProgramWasPaused`,
`aFixedRunEndsOnItsCalendarEndHoweverLongTheProgramWasPaused`,
`aPauseAddedAfterTheScheduleWasPlannedDoesNotRenumberTheRemainingDates`,
`theSameDatesPresentTheSamePlanDaysWhetherOrNotTheProgramWasPaused`) and the RED script two mutations that
restore the defect and the active-days reading. Two earlier tests were *corrected*, not deleted: they had
pinned the wrong reading (`…ResumesWhereItFroze` asserted the shifted `day-2`; it now asserts the calendar's
`day-1`) — a test asserting the defect is worse than no test.

## 7. Verification

All measured on the final bytes, with a fresh `--rerun-tasks` run whose JUnit XML `timestamp` was checked
against `date -u`:

| gate | result |
| --- | --- |
| pristine `origin/main` baseline (`9231bfc`, detached worktree, `:app:cleanTest :app:testDebugUnitTest --rerun-tasks`) | **231 classes / 1924 tests / 0 failures / 0 errors / 0 skipped** |
| branch, same command (after the semantics lock) | **234 classes / 1998 tests / 0 failures / 0 errors / 0 skipped** — **+3 classes / +74 tests** |
| freshness | newest XML `timestamp` `2026-09-19T09:04:31Z` vs `date -u` `09:05:22Z`; `BUILD SUCCESSFUL in 45s` (re-run **after** the RED mutation pass, to prove its restores) |
| structural cross-check | `grep -rl '@Test' app/src/test/java \| wc -l` = 234; `grep -rho '@Test' … \| wc -l` = 1998 |
| focused PR-7 suites (after the semantics lock) | `ProgramSchedulerTest` 35, `SlotPlannerTest` 30, `ProgramSchedulerArchitectureTest` 9 — 74 tests, 0 failures |
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

## 8. RED evidence

`scripts/program-scheduler-red-mutations.sh` applies one mutation at a time to the *production* sources,
reruns the focused scheduler suites, restores the file and proves the restoration by `md5sum -c`. Eighteen
rules plus a control, **19 caught / 0 missed** on the final bytes. Three of them are the RED mutations of
the settled semantics above: a superseded date planned again (§5.1), the plan counted along a pause-aware
walk and a pause superseding what it covers (§5.2), and a fixed run extended by the paused days (§5.3).

| mutation | rule it breaks |
| --- | --- |
| every later date is shifted by the number of misses | no whole-schedule sliding after a miss |
| the flexible rhythm is picked at random | deterministic generation |
| the flexible frequency is derived from the slots that exist | the stated frequency is authoritative |
| the Scheduler acquires a session repository | §33: never let the Scheduler create Session |
| an occupied date is planned a second time | one date holds one opportunity; no overwrite |
| paused dates are planned as if the program were running | a pause suppresses creation inside itself |
| missed detection ignores pauses | pause freezes missed-opportunity logic |
| today's opportunity is already missed | missed is measured against the pass date |
| every future opportunity is superseded | supersession is precise, not regeneration |
| the indefinite horizon is 14 days | §20: exactly 30 days of future planning |
| every date presents the first plan day | the plan's own days cycle in order |
| a pass mints a revision | §6: revisions are immutable |
| a completed Program is planned | completed Programs receive no future slots |
| an archived Program is planned | archive stops future planning (§29) |
| planning starts the Program | a planned start date does not start a Program |
| count the plan's days along a pause-aware walk | a pause renumbers nothing (the audit defect) |
| extend a fixed run by the paused days | `FixedDays` is a number of calendar days |
| make a pause supersede the opportunities it covers | a pause preserves what is inside it |

The highest-risk mutations, with the assertion text they produced on the final bytes (a mutation is only
evidence if the failure names what it measured). Every row was read back from the JUnit XML of its own run:

| mutation | failing tests | measured |
| --- | --- | --- |
| an occupied date is planned a second time (§5.1) | 25 | `aDateThatAlreadyHoldsAnOpportunityIsNeverPlannedAgainEvenAfterItWasSuperseded` and 24 others — a date that holds a slot gains a second one |
| the plan is counted along a pause-aware walk (§5.2) | 8 | `SlotPlannerTest.aPauseDoesNotRenumberThePlanForTheDatesThatFollowIt`: *"…a pause stops planning inside itself and renumbers nothing…"*; `ProgramSchedulerTest.theSameDatesPresentTheSamePlanDaysWhetherOrNotTheProgramWasPaused`: *"…a pause removes dates from the plan, it never renumbers it"* |
| a pause supersedes the opportunities it covers (§5.2) | 5 | `aPauseDoesNotDestroyAnOpportunityThatIsStillAhead`: *"nothing is superseded by a pause that is still ahead of the user expected:\<0\> but was:\<5\>"* |
| a fixed run is extended by the paused days (§5.3) | 4 | `aFixedRunEndsOnItsCalendarEndAndNotOnTheDateAPauseWouldPushItTo`: *"fourteen calendar days from the anchor, pause or no pause expected:\<2026-09-27\> but was:\<2026-09-28\>"* |
| paused dates are planned as if the program were running | 4 | `SlotPlannerTest.anOpenPauseFreezesPlanningEntirely`: *"an open pause covers every date from its start onward…"* |
| every future opportunity is superseded | 22 | `anOpportunityThatPassedIsMissedAndNoWorkIsInventedForIt`: *"the status is recorded on that slot and nowhere else: the other eight opportunities are still open… expected:\<8\> but was:\<0\>"* |
| the flexible frequency is derived from the slots that exist | 2 | `aFlexibleFrequencyIsPlannedFromTheStatedCountAndNotFromTheSlotsItAlreadyHas`: *"…expected:\<0\> but was:\<17\>"* |
| dates shifted by the number of misses | 1 | `aMissedOpportunityMovesNoOtherDateAndNoOtherPlanDay`: *"a miss does not slide the schedule: the dates a pass would add are the same dates, in the same order…"* |

One claim is expressed structurally rather than behaviourally, and the script says so out loud: **the
Scheduler creates no Session**. The mutation gives the Scheduler the session repository it would need, and
the architecture suite fails on the collaborator list — a Scheduler with no session runtime cannot write
one, which is exactly why the guarantee is the absence of the dependency (plus a whole-database row
census after real passes) rather than a mutated session write.


## 9. Remaining decisions to review

Three items that were listed here as unresolved decisions are now **settled semantics** — a superseded
date is never re-planned (§5.1), a pause preserves the opportunities inside it and renumbers nothing
(§5.2), and `FixedDays` is calendar duration (5.3). Each has a test named after the rule and a RED
mutation, and each was implemented before it was settled: locking them changed no behaviour.

What remains open:

1. **A pause covers whole days.** A pause stored as instants is read as covering the date it starts on
   and the date it ends on, even a 21:00 pause or a 09:00 resume. A half-covered day would have to
   answer "was 14:00 still an opportunity?", which is a question about the user's intention rather than
   about the plan.
2. **A slot that survives a revision change is not re-pointed at the new revision.** It keeps the
   `revisionId` and the plan day it was scheduled from — the domain says a slot names *the revision it
   was scheduled from*, and re-pointing is a rewrite of an existing row. The effective presentation of a
   slot is the current revision plus adjustments (§16), which a session snapshots when it starts.
3. **A completed/archived Program is refused entirely rather than reconciled.** §29 gives archiving the
   effect of stopping future planning; it says nothing about the unfilled opportunities already in the
   past. This stage writes nothing at all for such a Program, so declaring an opportunity missed *after*
   the program was archived is a separate decision the owner may want (§29's "retains all history" reads
   either way).
4. **Missing is date-based and attempt-agnostic.** An opportunity whose date passed while the program was
   not paused becomes `MISSED` whether or not a session was ever started for it; the slot's `attempts`
   and the session's own record are untouched, and a session that is still `IN_PROGRESS` may later
   complete the slot (§19's complete-workout transaction writes the outcome). Exempting slots with
   attempts would let an abandoned attempt keep an opportunity open forever.
5. **Planning is refused, not deferred, without an anchor.** A Program with neither an actual nor a
   planned start is refused (`NoSchedulingAnchor`) rather than planned from today — the same rule the
   creation entry point applies (`initialSlotsFor`). The alternative —
   seeding an anchor — is the Scheduler deciding when a user's program begins.
6. **`preview` mints the identities it reports.** A decided-but-unstored slot has to name itself to be
   reportable, so a preview consumes ids from the injected generator while writing nothing. If a caller
   needs previews that do not consume identity, that is a small change to the outcome shape.
7. **The 30-day horizon is planned for finite programs too, but its window is its own run.** A
   `FixedDays(3650)` revision is planned in one pass of ~1500 slots. The alternative (a horizon for
   every duration, extending forever) contradicts §20's sentence, which scopes the 30 days to indefinite
   programs; a cap for very long fixed programs is a product decision.
8. **The split between `ScheduleCalendar` (arithmetic) and `SlotPlanner` (decision)** is deployment
   convenience, not architecture: both are pure domain. If a later stage wants a different spread rule
   or a different week anchor, it is one function in `ScheduleCalendar`.

### What is deliberately *not* open

There is **no `RevisionConflict` mechanism**, and the stale-draft rule is unchanged: a scheduling pass
works from the Program's stored current revision, there is no draft, no optimistic-concurrency token and
no staleness check to fail, and `ProgramSchedulingRefusal` carries no conflict case. The editor's recorded
behaviour stands — a stale draft saves relative to what is stored (§30 step 6, decision 2 there), and
nothing in this stage introduces or depends on a conflict path.

## 10. Base

This branch is based on `origin/main` at `9231bfc`, the merge commit of PR 6
(`feat/program-manual-editor`), so the editor, the lifecycle, the composition root, the repositories and
the schema it builds on are all already in `main`. No rebase was needed; the diff is exactly this stage's
work plus the one additive construction in the composition root (`+33 / -0`).
