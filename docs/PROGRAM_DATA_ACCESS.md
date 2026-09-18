# Program System — data access (PR 3)

Scope: the **data-access layer** of the multi-Program architecture — DAOs, mappers and repositories for
the fifteen target tables that PR 2 added. This document records what was added, the boundary decisions
it had to make, what the tests prove, and the one contract gap this stage discovered.

Reference architecture: `docs/Monk Fitness — Program System Implementation Blueprint.MD` (cited below as
**§N**). Implementation order: §30 step 3, *DAO + mappers + repositories*. Companions:
`docs/PROGRAM_DOMAIN_FOUNDATION.md` (PR 1, the domain) and `docs/PROGRAM_ROOM_SCHEMA.md` (PR 2, the
schema).

This change is **data access only**. There is no `AppContainer`, no DI wiring, no use case, no ViewModel,
no UI, no lifecycle behaviour, no scheduler, no generator, no Focus Planner, no adaptive policy or
engine, no import/export and no legacy removal — §30 steps 4+ own all of that. Nothing here is
constructed by the app: the DAOs, mappers and repositories are new files plus one additive block of
`AppDatabase` accessors, and no existing code path reaches them.

---

## 1. The layer

```text
Room entity (PR 2)  ⇄  mapper (this PR)  ⇄  domain model (PR 1)
        ⇅
       DAO (this PR)  ⇄  repository (this PR)  ⇄  domain
```

| file | responsibility |
| --- | --- |
| `data/local/ProgramDao.kt` … `AdaptiveAdjustmentDao.kt` (15) | one DAO per target table: insert, the reads a repository needs, the one mutation a mutable row needs, and nothing else |
| `data/mapper/StoredValues.kt` | the stored representations: enum tokens by name with a loud failure, epoch millis, ISO dates |
| `data/mapper/ProgramMappers.kt` | `program` ⇄ `Program` |
| `data/mapper/PlanMappers.kt` | `program_revision` + `program_day` + `program_exercise` ⇄ `ProgramRevision` |
| `data/mapper/ScheduleMappers.kt` | `program_workout_slot` ⇄ `WorkoutSlot`, `program_pause` ⇄ `ProgramPause` |
| `data/mapper/SessionMappers.kt` | `workout_session` + `session_snapshot` + `session_snapshot_exercise` + `session_exercise` + `program_set_log` ⇄ `WorkoutSession` |
| `data/mapper/AdaptiveMappers.kt` | the target adaptive rows ⇄ `FamilyProgressionState`, `AdaptiveDecision`, `AdaptiveAdjustment` |
| `data/mapper/AppStateMappers.kt` | `app_state` ⇄ `AppState` |
| `data/repository/ProgramRepository.kt` | the Program aggregate, its atomic creation, its cascade delete |
| `data/repository/ProgramPlanRepository.kt` | reading a revision's plan, saving a new revision |
| `data/repository/ProgramScheduleRepository.kt` | slots and pause intervals |
| `data/repository/WorkoutSessionRepository.kt` | starting a session, reading it, confirming sets, finishing |
| `data/repository/ProgramAdaptiveRepository.kt` | the target adaptive tables |
| `data/repository/ProgramProgressRepository.kt` | the row-level facts Progress will read |
| `data/repository/AppStateRepository.kt` | the single global state row |
| `AppDatabase.kt` | fifteen additive DAO accessors; the shipped ones are untouched |
| `domain/program/AppState.kt`, `domain/adaptive/FamilyProgressionState.kt` | two new pure values (see §10.1) |

## 2. DAOs

One DAO per target table, everything else deliberately absent:

* **no business rule.** No DAO chooses a revision, decides a lifecycle transition, applies a policy,
  validates a schedule, interprets a generated/manual origin, resolves an exercise or decides whether a
  delete is allowed. Which status counts as "open" is a *parameter* rather than a literal in the SQL, so
  the vocabulary stays in the domain.
* **no history mutation.** `ProgramRevisionDao`, `ProgramDayDao`, `ProgramExerciseDao`,
  `ProgramAdaptiveDecisionDao` and `AdaptiveAdjustmentDao` expose no update and no delete at all: a
  revision is immutable (§6), and a decision and an adjustment are audit records (§16, §18). Their rows
  disappear only through the ownership cascade.
* **no amount of work.** `program_workout_slot` has no repetitions, duration, score or progress column,
  and the slot DAO returns identity, ownership, date, status, stamp and counts — which is what makes a
  missed opportunity unable to be read as a workout that scored zero (§12).
* **no second copy of a link.** The snapshot DAO never joins the live plan; the decision DAO stores no
  adjustment id (the adjustment row owns that link, §23); the adjustment DAO resolves it in that
  direction.
* **list reads are batched.** A session list is assembled with one query per table (`IN (:sessionIds)`)
  rather than one query per session, and every read carries a deterministic `ORDER BY` ending in the
  identity.
* **Room only.** A DAO imports `Room` and an entity; never the domain, a repository, a mapper or the UI
  (`ProgramDataAccessArchitectureTest`).
* **no reactive read yet.** No DAO returns a `Flow`: §24 allows one where a later UI read model genuinely
  needs reactive observation and the repository contract needs it, and nothing in this stage has such a
  consumer. Adding one later is additive.

## 3. Mappers

The boundary is `Room entity ⇄ domain model`, nothing else: no Android type, no repository call, no
database access, no clock, no id generation, no random, no business decision. Three rules carry most of
the meaning:

* **typed ids.** Every id crosses the boundary into its own inline value class (`ProgramId`, `RevisionId`,
  `ProgramDayId`, `ProgramExerciseId`, `SlotId`, `SessionId`, `SessionExerciseId`, `SetLogId`, `PauseId`,
  `AdjustmentId`, `DecisionId`). No entity declares a typed id, and no mapper hands a raw `String` to a
  domain constructor.
* **vocabulary by name, and unknown means invalid.** A stored token is resolved against the domain enum
  it belongs to; a token that is not a member fails loudly, naming the column, the value and the
  vocabulary. Nothing is mapped to `UNKNOWN`, to a default or to `null` on the way in, and no
  `enum.ordinal` appears anywhere.
* **values are total and deterministic.** Epoch milliseconds and ISO `YYYY-MM-DD` go through
  `ProgramTypeConverters`' own conventions (no second serialization), collections keep their canonical
  order, and a domain constructor is allowed to refuse what a row claims — the mapper does not sort away,
  clamp, repair or renumber anything.

One asymmetry is worth naming explicitly: `AdaptiveAdjustment` (the PR-1 domain value) carries the
adjustment, the decision it came from, the slot, the changed element and the supersession chain, but not
the Program and revision — while `adaptive_adjustment` stores both as ownership columns (§29). Writing
therefore takes the owner from the decision in hand, and reading drops the duplicate copy, which is
reconstructible from the decision. Nothing is lost: the link is stored once, on the row that owns it.

The plan mapper is the one with a real asymmetry, and it is documented in the file: loading assembles
rows in the revision's own order and refuses to drop one, while saving derives positions from the ordered
value (a day's own position, an element's place in its day, since a plan element is an occurrence *in an
order*, §9).

**Prescriptions are per set, never scalar.** `12 / 10 / 8 / 6` and `30 / 30 / 45` cross the boundary as
their own list, in the dimension recorded beside them. The three dimensions §10 names and does not
implement (`SET_BASED`, `DIFFICULTY_BASED`, `REST_BASED`) are **representable in storage and not loadable
into the domain**: they have no subtype, so a row in one of them fails loudly instead of being read as
something it is not.

## 4. Repositories

§24's split, with one rename and one addition:

| §24 name | this PR | why |
| --- | --- | --- |
| `ProgramRepository` | `ProgramRepository` | the aggregate, creation, deletion |
| `ProgramPlanRepository` | `ProgramPlanRepository` | revisions and plans |
| `ProgramScheduleRepository` | `ProgramScheduleRepository` | slots and pauses |
| `WorkoutSessionRepository` | `WorkoutSessionRepository` | sessions, snapshots, sets |
| `AdaptiveRepository` | **`ProgramAdaptiveRepository`** | the name is taken by the shipped Stage-1 adapter — see §7 |
| `ProgramProgressRepository` | `ProgramProgressRepository` | row-level progress reads |
| — | `AppStateRepository` | the global single-row state (see §10.2) |

A repository may coordinate several DAOs, map in both directions, own a transaction, return domain
models and surface failures. It may **not** choose policy, calculate an adaptive signal, schedule a date,
generate a plan, decide a lifecycle transition, swallow a failure or mutate history to make current state
convenient. `ProgramRepository` in particular exposes no `start`, `pause`, `resume`, `complete`,
`archive`, `select` or "is this deletable" operation: those are §3 and §29 decisions owned by the layer
that will call it.

`ProgramProgressRepository` deserves a note, because §21 lists a lot: it exposes **counts and ordered
identities only** — slots per status, sessions per status, confirmed sets, session ids. Frequency,
volume, focus and family distribution, average duration, performance progression, PRs and streaks are
computations over this data and belong to §30 step 9; and there is deliberately no scalar amount of work
anywhere, because repetitions are not comparable across exercises and dimensions (§17).

## 5. Transactions

Every atomic operation §27 requires of this layer is provided as a persistence primitive, with the
transaction supplied by the composition root (`AppDatabase.withTransaction` in production, the engine's
own `BEGIN`/`COMMIT`/`ROLLBACK` in the suites):

| operation | repository | guarantees |
| --- | --- | --- |
| Create → Program + Revision + plan + initial Slots | `ProgramRepository.createProgram` | one transaction; a failure anywhere leaves no Program, no revision, no day, no element and no slot |
| Save Editor → new Revision (+ the pointer) | `ProgramPlanRepository.saveNewRevision` | new rows, never a rewrite; the `currentRevisionId` pointer moves in the same transaction or not at all. Future-slot reconciliation is **not** here (§20) |
| Start Workout → Session + complete Snapshot | `WorkoutSessionRepository.startSession` | session, snapshot, captured elements and occurrences land together |
| Confirm Set → SetLog | `WorkoutSessionRepository.appendSet` | appends one row; the occurrence must exist |
| Complete Workout → Session + Slot | `WorkoutSessionRepository.finishSession` | the session and the opportunity are one fact |
| Complete Workout + Adaptive | `ProgramAdaptiveRepository.persistDecision` | decision and its adjustment together; the adaptive half of §27's completion transaction is composed by the session-runtime stage (§30 steps 8, 12) |
| Delete Program → complete ownership cascade | `ProgramRepository.deleteProgram` | the delete is the **schema's** cascade, not a hand-written child sweep |

## 6. Error contract

§28's typed hierarchy is consumed by later use cases; this layer's job is to keep the three situations
distinguishable rather than to collapse them:

* **missing data** → `null` or an empty list (`programById`, `revisionById`, `familyState`, …);
* **invalid persisted data** → `IllegalArgumentException` from the mapper or from a domain constructor,
  naming the column, the row and the rule (an unknown token, a prescription in an unimplemented
  dimension, a `COMPLETED` slot without a stamp, a session without its snapshot, a set gap, a scope that
  disagrees with its target id, an `APPLIED` decision whose adjustment row is gone);
* **database failure** → whatever the engine raised, uncaught: a constraint violation, a foreign key
  refusal, a planted DAO failure. Nothing in this layer converts one into an empty result, a `null` or a
  `false`, and no broad `catch` exists anywhere in it.

## 7. The two persistence generations

```text
legacy Stage-1 persistence   →  legacy DAOs / AdaptiveRepository      (untouched, still shipped)
Program System persistence   →  target DAOs / Program* repositories   (this PR)
```

`AdaptiveRepository` already exists, is shipped and reads/writes `family_progression_state` and
`adaptive_decision_record` keyed by the legacy revision integer. It is neither renamed nor retrofitted:
the target layer is `ProgramAdaptiveRepository` and owns only `program_family_progression_state`,
`program_adaptive_decision_record` and `adaptive_adjustment`. The two are physically and semantically
separate until §30 step 15, when the legacy retirement can collapse the names. A target repository never
falls back to a legacy table, no legacy DAO is rewritten, and `ProgramMaintenance`'s Full-Reset table set
is unchanged (wiring the Program System's own deletion is a later stage).

Two target tables also required new *names* of their own for symmetry with the shipped ones —
`program_set_log` (not `set_log`) and `program_family_progression_state` — exactly as the schema PR
established.

## 8. What the tests prove

The repository has no Robolectric, no instrumentation source set and no `androidx.room.testing`, so the
DAO behaviour that matters here cannot be exercised through Room's generated code on the JVM. The suites
therefore drive **the DAOs' own SQL on a real SQLite engine** (test-scope `org.xerial:sqlite-jdbc`, the
same driver mechanism the PR-2 schema suites use): `ProgramDaoSql` carries every `@Query` literal
verbatim, `ProgramTestDoubles` implements the fifteen DAO interfaces against a migrated version-8
database, and `ProgramDataAccessArchitectureTest` asserts the correspondence in both directions — every
query a DAO declares is executed, and every statement the harness executes is declared by a DAO.

| suite | proves |
| --- | --- |
| `data/mapper/*Test` (6 classes) | mapper round trips in both directions for every target pair: typed ids, every enum token, dates, times, nulls, ordered collections, per-set prescriptions, occurrence identity, the rest day, the two duration forms, the supersession chain, the adjustment capture — and the loud failures (unknown token, reserved dimension, missing children, invalid rows) |
| `ProgramRepositoryTest` | the whole-Program graph round trip (two training days with a rest day between them, an exercise occurring twice, per-set prescriptions); creation atomicity with a planted mid-graph failure (no Program, no revision, no day, no element, no slot left); the cascade delete with a second Program and global rows surviving; the `selectedProgramId` `NO ACTION` refusal propagated and then allowed once the selection moved; `nextProgramId` `SET NULL` with its row surviving; constraint failures reported rather than absorbed |
| `ProgramPlanRepositoryTest` | a saved revision is new rows and the previous revision is byte-identical afterwards; the pointer moves in the same transaction; a failed save leaves neither rows nor a moved pointer |
| `ProgramScheduleRepositoryTest` | slots come back in planned-date order with their plan day and the attempts started for them; a stored slot keeps the date the caller decided on; recording a `MISSED` outcome changes that slot and shifts no other date; pause intervals are kept and closed without rewriting them; the repository exposes no scheduling decision |
| `PlanMapperTest`, `ProgramPlanRepositoryTest` | both schedule forms round-trip losslessly through the corrected schema: every frequency in the domain's range survives `domain → rows → domain` and `rows → domain → rows`, a weekday schedule stores no frequency, and an unrepresentable frequency row is refused rather than repaired (§9) |
| `WorkoutSessionRepositoryTest` | **the session keeps its captured presentation after the live plan is rewritten underneath it** (element re-pointed, prescription replaced, a new revision saved) — §19; sets in set order, with a gap refused rather than zero-filled; two attempts at one slot both loading; start-session atomicity; finish-session writing session and slot together; cancelled ≠ completed |
| `ProgramAdaptiveRepositoryTest` | the target repository reads and writes only the target tables while the Stage-1 rows stay byte-identical; the decision/adjustment pair lands together or not at all; history is append-only and the supersession chain is read, never rewritten; a filtered-out decision stays with `NOT_APPLIED`; an applied decision whose adjustment is gone is invalid data; the state stamp comes from the injected clock |
| `ProgramProgressRepositoryTest` | the counts are a row census with zeroes, scoped by Program; no amount of work and no derived measure is exposed |
| `ProgramDataAccessArchitectureTest` | the layering, the absence of a self-acquired database and of scheduler/generator/policy/clock vocabulary, the Stage-1/target separation in both directions, no entity in a repository's public surface, no table-wide update or delete, no UI or ViewModel reach into the data layer, and that every entity declares exactly its table's columns |

The counts, the RED evidence (the deliberate breaks each rule guards) and the exact gate output are in
the PR body.

## 9. The flexible-frequency gap this stage reported, and how it is closed

`ProgramSchedule.FlexiblePerWeek` carries the deterministic `sessionsPerWeek` §20 requires. The version-8
`program_revision` table stored the schedule *form* (`FLEXIBLE_PER_WEEK`) and the weekday set for the other
form, but **no column held the frequency**, so such a revision could not be persisted or loaded
faithfully. This stage refused both directions loudly instead of losing or inventing the number
(`PlanMappers.FLEXIBLE_FREQUENCY_GAP`, pinned by two tests) and reported the gap as a blocker.

The blocker was taken up as a separate, additive schema correction and has **landed on `main` before this
stage** (`docs/PROGRAM_SCHEDULE_FREQUENCY_CORRECTION.md`): `program_revision.scheduleSessionsPerWeek` is a
nullable `INTEGER`, `FIXED_WEEKDAYS` stores `null` and `FLEXIBLE_PER_WEEK` stores the number it runs at,
migrated by the strictly additive step `8 → 9`.

The mapper therefore stores and reads the frequency in both directions, the refusal constant is gone, and
the two tests that pinned the gap now pin the lossless round trip — `PlanMapperTest` walks every value of
`ProgramRevisionEntity.SESSIONS_PER_WEEK_RANGE` through `domain → rows → domain` and `rows → domain →
rows`, and refuses an unrepresentable frequency row (a missing number, or one outside the domain's own
range) rather than repairing it. `ProgramPlanRepositoryTest` proves the same path through the DAO, the
repository and the database: a saved flexible revision keeps its frequency in the row, a weekday schedule
keeps `null`, and both load back as the schedules they were.

## 10. Decisions for the owner

### 10.1 Two values were added to the domain because the mapper boundary needs a domain side

PR 1 shipped the adaptive decision vocabulary, the foundation types and the Program System's models, but
not a *stored state* model and not an application-state value. The mapper boundary is
`entity ⇄ domain`, so both were added as pure values with no behaviour:

* `domain/adaptive/FamilyProgressionState` — exactly the six fields §23 names, deliberately **without**
  the Stage-1 hysteresis counters, policy version or legacy revision key, and without importing the
  pilot's `-2..+2` level bound (the target engine's axis is not this stage's design, §30 step 11);
* `domain/program/AppState` — `selectedProgramId`, `nextProgramId`, `nextProgramAutoStart` and the
  single-row identity, with no fallback, no auto-start evaluation and no lifecycle transition.

Both are asserted structurally by tests (their field sets are pinned), and both are additive: when the
later stages give them consumers, nothing about this shape has to change.

### 10.2 `AppStateRepository` was added; §24 does not name it

The blueprint's list has no home for the global single-row state. Putting it in `ProgramRepository` would
make a global fact look Program-owned — the exact conflation §21 forbids — so it has its own two-method
repository. The alternative (a fourth "misc" repository) was rejected as worse.

### 10.3 (resolved) the flexible-frequency revision, once refused in both directions

Recorded rather than silently resolved, because the fix was a schema change outside this stage's scope; the
owner took it up as the schema correction `docs/PROGRAM_SCHEDULE_FREQUENCY_CORRECTION.md`, which merged
before this stage, and §9 records the mapper work that closed it here.

## 11. Deferred to later stages

* `AppContainer` / DI and any production construction of these repositories (§30 step 4).
* Program lifecycle behaviour, selection fallback, "may this Program be deleted" and My Programs
  (§30 step 5) — the repository surfaces the database's refusal and stops there.
* The manual editor and its draft persistence (§30 step 6), the scheduler's slot generation, missed
  detection, supersession and horizon extension (§30 step 7) — `addSlots` stores what a caller decided.
* Session runtime wiring (§30 step 8): the composition of `Complete Workout + Adaptive` in one unit of
  work, the "at most one `IN_PROGRESS` session per slot" rule and the idempotency window of a
  finalization.
* Progress/History computations and any aggregation view (§30 step 9).
* The generator's reconciliation, pin/override preservation and the Focus Planner (§30 step 10).
* The adaptive engine, its integration, the policy and the load guard (§30 steps 11–12).
* Import/export/Share and their DTOs (§30 step 13).
* The retirement of the Stage-1 tables and the collapse of the two adaptive repository names (§30 step 15).
