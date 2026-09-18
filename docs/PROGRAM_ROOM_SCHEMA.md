# Program System — Room schema (PR 2)

Scope: the **new Room persistence schema** of the multi-Program architecture — §30 step 2, *New Room
schema*. This document records what was added, the physical naming decisions it had to make, why two
generations of adaptive persistence coexist, and what is deliberately absent. It is the schema
companion to `docs/PROGRAM_DOMAIN_FOUNDATION.md` (PR 1, §30 step 1).

Reference architecture: `docs/Monk Fitness — Program System Implementation Blueprint.MD` (cited below
as **§N**).

This change adds **persistence only**. There is no DAO, no repository, no mapper, no use case, no
`AppContainer` wiring, no ViewModel, no UI, no scheduler, no Program lifecycle logic, no generator, no
Focus Planner, no adaptive behaviour, no import/export and no legacy removal — §30 steps 3+ own all of
that, and nothing here is reachable from the app's existing code paths.

---

## 1. Database version and migration

```text
version 7  →  version 8          MIGRATION_7_8, purely additive
```

The migration executes exactly 37 statements: one `CREATE TABLE` for each of the fifteen target tables
and one `CREATE INDEX` for each of the twenty-two declared indices. It contains **no** `DROP`, `DELETE`,
`ALTER`, `UPDATE`, `INSERT` or `RENAME` statement, names no pre-existing table (and not Room's own
`room_master_table`, whose identity hash Room rewrites itself after a successful upgrade), and creates
only empty tables.

There is deliberately **no legacy program/history migration** (§23, §30 step 15). No legacy row is
converted into a Program, a Revision or a Session; no placeholder Program is backfilled. The migration
statement *is* the DDL Room generates for the fifteen entities — column order, types, nullability, keys,
indices and delete actions included — because Room validates a migrated schema against its own
expectations on open: a hand-written table that differed only in a `NOT NULL` flag would crash the
upgrade instead of failing silently.

## 2. The schema map

| §23 entity | entity class | table | identity | owner |
| --- | --- | --- | --- | --- |
| Program | `ProgramEntity` | `program` | `programId` | — |
| AppState | `AppStateEntity` | `app_state` | `id` (single row) | global |
| ProgramRevision | `ProgramRevisionEntity` | `program_revision` | `revisionId` | `program` |
| ProgramDay | `ProgramDayEntity` | `program_day` | `programDayId` | `program_revision` |
| ProgramExercise | `ProgramExerciseEntity` | `program_exercise` | `programExerciseId` | `program_day` |
| ProgramWorkoutSlot | `ProgramWorkoutSlotEntity` | `program_workout_slot` | `slotId` | `program` / `program_revision` / `program_day` |
| WorkoutSession | `WorkoutSessionEntity` | `workout_session` | `sessionId` | `program` / slot / revision |
| — (snapshot capture) | `SessionSnapshotEntity` | `session_snapshot` | `sessionId` | `workout_session` |
| — (snapshot elements) | `SessionSnapshotExerciseEntity` | `session_snapshot_exercise` | `sessionId` + `programExerciseId` | `session_snapshot` |
| SessionExercise | `SessionExerciseEntity` | `session_exercise` | `sessionExerciseId` | `workout_session` |
| SetLog | `SetLogEntity` | `program_set_log` | `setLogId` | `session_exercise` |
| ProgramPause | `ProgramPauseEntity` | `program_pause` | `pauseId` | `program` |
| FamilyProgressionState | `FamilyProgressionStateEntity` | `program_family_progression_state` | `revisionId` + `familyId` | `program_revision` |
| AdaptiveDecisionRecord | `AdaptiveDecisionRecordEntity` | `program_adaptive_decision_record` | `decisionId` | `program` / revision / slot |
| AdaptiveAdjustment | `AdaptiveAdjustmentEntity` | `adaptive_adjustment` | `adjustmentId` | decision / program / revision / slot |

Global entities keep their existing tables and are **not** Program-owned: the Exercise Library is not a
table at all (`exerciseId` stays an opaque library key), and `body_weight_log`, `meal_cycles`, `meals`,
`shopping_items` and the DataStore-backed global settings are untouched. No foreign key in the target
schema points at them, and none of them cascades with a Program (§29).

### The two auxiliary tables

`session_snapshot` and `session_snapshot_exercise` are not §23 entities; they exist because §19 requires
a started session to retain **what was presented** at session start. The snapshot is stored as its own
rows, with its own copies of the presented exercise and prescription and with **no foreign key into
`program_revision`, `program_day` or `program_exercise`** — a live join would let a later revision, edit
or superseding adjustment re-explain a workout that already happened. The snapshot's identity triple
(slot, Program, revision) is not duplicated: the domain binds a session to a snapshot naming the same
three, and `workout_session` holds them.

## 3. Physical table naming and transitional coexistence

Three target tables cannot use their §23 entity's own name, because the version-2..7 database already
owns it. The rule applied is: **when the physical name is taken, the target table takes the `program_`
ownership prefix.** Nothing shipped is renamed, reshaped or dropped.

| shipped table | target table | why coexistence is necessary |
| --- | --- | --- |
| `set_log` | `program_set_log` | The legacy logging row is `(exerciseId, repsCompleted, durationSeconds, timestamp, sessionDate)`. The target set row is one confirmed set of one session exercise — a different shape with a different meaning (§19). Reusing the table would silently reinterpret legacy rows. |
| `family_progression_state` | `program_family_progression_state` | The Stage-1 table is keyed by the **legacy revision integer** (`programRevision`, matching `SettingsManager.PROGRAM_REVISION`) and is read and written by shipped DAO/repository code. The Program System's identity is the typed `revisionId` of a real `program_revision` row (§1, §23); no value of one is expressible as the other, so reshaping the shipped table would change its contract and pull the adaptive integration into this stage. |
| `adaptive_decision_record` | `program_adaptive_decision_record` | Likewise Stage-1: keyed by the revision integer and describing a decision window on the program calendar (`cycleNumber`, `programDay`). The target decision is about one slot of one revision, carries its own identity, and records the grounds it was made on. |

`adaptive_adjustment` had no collision and keeps its §23 name.

The two Stage-1 tables are **untouched**: same columns, same primary keys, same stored vocabulary, and
still writable after the upgrade (asserted in `ProgramMigrationPreservationTest`). `ProgramMaintenance`'s
`clearedTables` deliberately does **not** gain the target tables — the legacy C3 Full Reset is not the
Program System's delete, and wiring that is a later stage.

This physical coexistence is transitional persistence, not two domain architectures: nothing in the
target schema reads or writes the Stage-1 tables, and §30 step 15 removes the old persistence when its
readers are gone.

## 4. The ownership graph and its delete actions

```text
Program
 ├─ program_revision ─ program_day ─ program_exercise
 ├─ program_workout_slot ─ workout_session ─ session_snapshot ─ session_snapshot_exercise
 │                                        └ session_exercise ─ program_set_log
 ├─ program_pause
 ├─ program_family_progression_state (through program_revision)
 └─ program_adaptive_decision_record ─ adaptive_adjustment
```

Every one of those edges is an explicit foreign key with `ON DELETE CASCADE`, so a Program is destroyed
atomically and completely (§27, §29). Two references deliberately do not cascade:

* `app_state.nextProgramId → program` is `ON DELETE SET NULL`, the blueprint's explicit exception:
  "start this program next" is a user choice about a program, and it is cleared when that program is
  deleted;
* `app_state.selectedProgramId → program` is `ON DELETE NO ACTION`, so deleting the *selected* Program
  is **refused by the database** until the lifecycle has chosen the fallback (§3: the Standard Program
  is the technical fallback; archiving the selected Program requires choosing another first). A
  `SET NULL` there would silently erase which program the user was in — the schema makes that
  impossible and leaves the decision to the layer that owns it.

`program.currentRevisionId` is intentionally **not** a foreign key. Create/copy/import produce a Program
together with its first revision in one transaction (§27), and a mutual key between the two tables could
not be satisfied by any insert order; ownership runs the other way, through `program_revision.programId`.

`adaptive_adjustment.supersedesAdjustmentId` is intentionally not a foreign key either: a constraint
there would either cascade (deleting a superseded adjustment would delete its successor) or rewrite the
successor when the earlier row is removed, and an audit record that can be rewritten by another row's
removal is not an audit record (§16).

## 5. Prescription storage

`perSetTargets` stores the ordered list of every set's target in the dimension recorded beside it
(`prescriptionDimension`). `12 / 10 / 8 / 6` and `30 / 30 / 45` are therefore four and three numbers,
not a uniform target with overrides, and never a single scalar — a collapsed scalar cannot answer
`targetForSet(3)` and cannot be expanded back (§10). The set count is the list's length, so no separate
"how many sets" column can contradict it.

The dimension token is one of the five §10 names — `REP_BASED`, `TIME_BASED`, `SET_BASED`,
`DIFFICULTY_BASED`, `REST_BASED`. The last three are **representable and unimplemented**: no algorithm,
coefficient or unit is invented for them here, and no rest column is added (§10 is the stage that owns
them).

Three further columns store a converted collection rather than a scalar: the revision's
`scheduleWeekdays` (canonical ascending week order), a snapshot's `appliedAdjustmentIds` (application
order), and the adjustment's before/after prescriptions. `ProgramTypeConverters` owns those forms and is
pinned token for token in the schema tests.

## 6. What the schema deliberately cannot represent

* **No amount of work on a slot.** `program_workout_slot` holds identity, ownership, the planned date,
  the status and a completion stamp — no repetitions, sets, duration, amount, progress or score. A
  `MISSED` slot is therefore not a workout that scored zero; the absence is the representation (§12).
* **No mode, plan, cycle or selection on a Program.** Mode, duration, schedule and the plan are revision
  content; selection is `app_state`; a cycle number, a session date and a day number are never
  ownership (§1, §23).
* **No legacy identity anywhere.** Every primary key is the entity's own typed id — never
  `cycleNumber`, `sessionDate`, `programDay`, `revisionNumber` or the legacy revision integer. A
  position orders rows; it never identifies one.
* **No second copy of a link.** An applied decision and its adjustment are related once, by the
  adjustment's `decisionId`; the pair is written in one transaction (§27), so there is no back-pointer
  that could disagree.
* **No domain type in a data-layer type.** The entities and the converters import nothing from
  `com.monkfitness.app.domain` and hold no domain value object: ids are stored as their wrapped values,
  vocabulary as the enum member's own name, time as epoch milliseconds and dates as `YYYY-MM-DD` strings.
  The mapper layer (§30 step 3) translates.
* **No unique constraint that forbids valid architecture**: a slot may carry several session attempts,
  an exercise may occur several times in one day, and a family has one state per revision.

## 7. Entity-level guards (and what they are not)

Five entities carry a `require` in their `init` block. They are representability guards mirroring
construction rules the domain already owns, not behaviour:

| entity | guard |
| --- | --- |
| `SetLogEntity` | a set is measured in repetitions or in time; a set that was not performed is absent, never a row of zeroes |
| `ProgramWorkoutSlotEntity` | only a `COMPLETED` slot carries a completion stamp, and a `COMPLETED` slot carries one |
| `WorkoutSessionEntity` | `IN_PROGRESS` ⇔ no finish stamp; a session cannot finish before it started |
| `ProgramRevisionEntity` | the duration and schedule discriminators agree with their payloads; revisions are numbered from 1 |
| `AdaptiveAdjustmentEntity` | an adjustment changes something and cannot supersede itself |

No other entity validates business rules: ranges, lifecycle agreement and cross-row rules belong to the
mapper and the repositories, which this stage does not write.

## 8. What the schema tests prove

| suite | proves |
| --- | --- |
| `ProgramSchemaTest` (36 tests) | the entity set is the §23 set plus the two documented snapshot tables; the database is at version 8 with every migration registered; the migration executes exactly the statements Room generates for the target entities, creates each table once and names no shipped table; no statement drops, deletes, alters, updates, inserts or renames; each prohibited representation is absent (slot amounts, Program mode/plan/cycle/selection, legacy coordinates used as identity); the presentation is stored with the session rather than joined to the live plan; a slot may hold several attempts; an exercise may repeat in a day; the target set log is not the legacy one; the adaptive target pair coexists with the Stage-1 pair; every foreign key and every delete action is the ownership graph; every identity and every column is declared as the contract says; the stored vocabulary is the domain's own enum names; entities import no domain type; the five guards fire; per-set prescriptions, weekday order and adjustment captures survive storage without collapsing. |
| `ProgramMigrationPreservationTest` (15 tests) | a populated version-7 database upgraded by the production migration on a real SQLite engine: every shipped table survives, every representative legacy row survives byte for byte, no legacy table definition or index is rewritten, the Stage-1 adaptive tables are still writable, the migrated DDL equals the DDL Room expects, and the primary keys, column types/nullability, unique indices and foreign keys SQLite records are the ones the architecture requires. |
| `ProgramOwnershipCascadeTest` (9 tests) | two complete Programs in one database, one deleted: every owned row of that program disappears, the other program's rows are untouched, `nextProgramId` is nulled, deleting the selected Program is refused until the selection is handled, a slot takes several attempts, an exercise repeats in a day, the identity coordinates refuse duplicates, and a delete leaves no orphan at the bottom of the graph. |

The repository has no Robolectric, no instrumentation source set and no `androidx.room.testing`, so the
migration and ownership claims are executed on a real SQLite engine through a test-scope driver
(`org.xerial:sqlite-jdbc`) rather than a simulated one — see the note in `app/build.gradle.kts`. Room's
own expectation of the migrated schema is pinned by comparing the migration's statements, the entities'
generated DDL and a live `sqlite_master` against each other.

## 9. Deferred to later stages

* DAOs, mappers and repositories for every table added here (§30 step 3) — including any DAO for the
  target adaptive pair, which this PR deliberately does not add.
* `AppContainer`/DI wiring (§30 step 4).
* Program lifecycle, My Programs, the editor, the scheduler, the session runtime, progress/history, the
  generated planner, the adaptive engine and its integration, import/export (§30 steps 5–13).
* The removal of the legacy program/history persistence and of the Stage-1 adaptive tables (§30 step 15).
* The user-facing date format is not decided here: dates are stored as `YYYY-MM-DD` ISO strings, the
  convention the shipped `SetLog.sessionDate` and `BodyWeightEntry.date` already use.
