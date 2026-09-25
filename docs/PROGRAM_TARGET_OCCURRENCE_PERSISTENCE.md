# Program — target occurrence persistence (§30 step 14)

## The gap this closes

Phases 8–13 made a target occurrence *identifiable* in storage and stopped there. A persisted target
slot remembers exactly one thing about the occurrence it presents:

```text
program_workout_slot.targetOccurrenceKey    the occurrence's own identity
```

But a `PlannedOccurrence` is more than its identity. It also carries:

```text
plannedFor      the calendar date it was planned for
components      the ordered (ruleId, workoutId) list that says *what* the occurrence is
```

A slot row has no column for either. So until this phase, the semantics of a persisted occurrence
existed only in the in-memory value that produced the slot, and any later read-back had to invent
them. There were exactly three available inventions, and all three are lies:

* **parse `occurrenceKey`** — the composer's key happens to look like `rule:date`, or
  `combined:date:ruleA,ruleB`. Reading a rule or a workout out of that text would be reading a
  coincidence of one producer's formatting as a serialization. The key is an opaque token.
* **derive from a `ProgramDayId`** — a slot does know its plan day. But a plan day's identity, its
  position and its name describe a *plan*, not a rule and a workout, and one plan day can present
  several occurrences.
* **consult the legacy scheduler** — the legacy `ProgramScheduler` / `SlotPlanner` /
  `ScheduleCalendar` generate *their own* occurrences from a `ProgramSchedule`. Those are not the
  target occurrences at all.

This phase makes the payload a **stored fact** instead. The result is that a later phase can obtain
the exact persisted `PlannedOccurrence` semantics without parsing a key, deriving an identity, or
reaching into the legacy scheduler.

## What was added

| File | Role |
| --- | --- |
| `domain/program/target/TargetOccurrencePersistence.kt` | `PersistedTargetOccurrence` and the typed conflict refusal |
| `data/model/ProgramTargetOccurrenceEntity.kt` | the occurrence's parent row |
| `data/model/ProgramTargetOccurrenceComponentEntity.kt` | its ordered component rows |
| `data/local/ProgramTargetOccurrenceDao.kt` | inserts and reads; no `UPDATE`, no `DELETE` |
| `data/mapper/TargetOccurrenceMappers.kt` | rows ⇄ `PlannedOccurrence`, exactly |
| `data/repository/TargetScheduleOccurrenceRepository.kt` | the store/refuse and read-back contract |

Modified: `TargetScheduleSlotPersister` (the atomic boundary), `AppDatabase` (version 14 and
`MIGRATION_13_14`), `AppContainer` (wiring).

## Storage design

Two tables, because the component list is ordered:

```text
program_target_occurrence
  programId      TEXT     PRIMARY KEY
  occurrenceKey  TEXT     PRIMARY KEY
  plannedFor     TEXT

program_target_occurrence_component
  programId      TEXT     PRIMARY KEY   } the composite foreign key:
  occurrenceKey  TEXT     PRIMARY KEY   } a component belongs to the
  position       INTEGER  PRIMARY KEY   } occurrence, and to the *pair*
  ruleId         TEXT
  workoutId      TEXT
```

### Why rows and not one serialized column

The alternative — a single `TEXT` column holding an encoded component list — was rejected because
each representation loses a different thing:

* **Order stops being stored.** The presenter's order is part of the payload and part of the primary
  key here, so it cannot be lost or contradicted. A blob would have to be *parsed* to recover the
  order, so the read-back would depend on an encoding convention rather than on stored rows.
* **A field stops being a field.** `ruleId` and `workoutId` are two independent TEXT columns, so
  reading either is a column fetch rather than a delimiter split.
* **A component becomes fabricable.** A short list, a padded list, a list with a placeholder token —
  all representable in a blob, none representable here. There is no nullable component row and no
  default identity: an occurrence either has its real components or does not exist.

### The composite foreign key

`program_target_occurrence_component` references its parent by the **whole pair**
`(programId, occurrenceKey)`, and the parent's primary key is that same pair. This is the one key in
the schema that spans two columns, which is why `ProgramSchemaFixture.ExpectedForeignKey` models
child and parent columns as lists (`ExpectedForeignKey.single(...)` is the one-column case every other
key uses) and why `SqliteTestDatabase.foreignKeys` groups SQLite's per-column `pragma_foreign_key_list`
rows back into one key.

It could not be a single column. `occurrenceKey` alone is not unique across Programs — that
independence is the point of the identity — so a one-column key on `occurrenceKey` would either be
unenforceable or would silently forbid two Programs from holding the same key. A one-column key on
`programId` would leave `occurrenceKey` unreferenced, so a component row could name a Program its own
occurrence does not belong to.

## Identity and conflict semantics

The sole target semantic identity is:

```text
(programId, occurrenceKey)
```

consistent with Stage 8, where the same pair became the slot's unique target identity. Two Programs
may hold the same occurrence key independently; one Program cannot hold it twice; and because the
pair *is* the primary key, no statement can re-point a stored record at another Program.

For a repeated write of an existing identity:

| Second write's payload | Result |
| --- | --- |
| identical (key, `plannedFor`, ordered components) | idempotent no-op — nothing is written |
| different `plannedFor` | `ConflictingSemanticPayload` |
| different component identity | `ConflictingSemanticPayload` |
| different component **order** | `ConflictingSemanticPayload` |
| a component added or removed | `ConflictingSemanticPayload` |

The stored record is **never** overwritten. There is no `UPDATE` statement in the DAO at all, so
"never silently overwritten" is a property of the code rather than a promise about it. Order is
compared as an ordered list, not as a set: a reordered payload is a *different* payload.

Not used as a substitute identity anywhere: `slotId`, `revisionId`, `programDayId`, a plan day's
`position` or `name`, a date, a weekday, a list index, or a parsed occurrence key.

## Atomicity

The target slot and the target occurrence it presents are one unit:

```text
one transaction
    scheduleRepository.addSlots(created)
    for each presented occurrence: occurrenceRepository.store(...)
commit
```

`TargetScheduleSlotPersister` takes the transaction runner as a **port**
(`inTransaction: suspend (suspend () -> Unit) -> Unit`), not a database — it still reaches storage
only through the two repositories, exactly as the Stage 10 boundary was written. Production passes
`AppDatabase.withTransaction`; the unit tests pass the SQLite engine's real
`BEGIN`/`COMMIT`/`ROLLBACK`, so atomicity is decided by the engine.

A failure on the **second** leg — the component insert, after both the slot row and the occurrence's
parent row have been written — is the measurement. A fault on the first leg would only prove nothing
started; a fault on the second proves the rollback reaches back and undoes rows that genuinely landed.

The semantic repository opens no transaction of its own: the caller is the layer that knows the slot
and the occurrence are one unit.

## The `ExistingOccurrence` boundary

This phase stores **semantic schedule data only**:

```text
PersistedTargetOccurrence   semantic schedule data
ExistingOccurrence          semantic schedule data + execution state + actual results
```

They are deliberately different types, and nothing here reconstructs the second. The reasons are
factual, not stylistic:

* `WorkoutSlot.status` has no member that distinguishes `STARTED` from `CANCELLED`;
* attempts live in `WorkoutSession`, not on the slot row;
* the work actually performed lives in the session graph and the set log.

Reconstructing execution is a later phase's question with a different set of rules, and inventing one
here would be inventing it twice. Nothing in this phase's code derives an `OccurrenceExecution`, a
session attempt or an `ActualResult`.

## Migration

`MIGRATION_13_14` executes exactly three statements: the two `CREATE TABLE`s and the component
table's lookup index. It writes no row, changes no existing one, and issues no `UPDATE`, `INSERT`,
`DELETE`, `ALTER` or `RENAME`.

There is deliberately **no backfill** from `program_workout_slot`. A slot carries no components, so
any such row would have to invent them — and a row that invents its own payload is worse than an
absent one, because a later read would return the invention believing it was stored. A target
occurrence that has not been persisted through this phase's path simply has no semantic record yet,
and its absence is honest.

The `CASCADE` foreign keys are the ownership graph, not a convenience: an occurrence is destroyed
with its Program, and its components with the occurrence.

## Legacy isolation

`ProgramScheduler`, `SlotPlanner`, `ScheduleCalendar`, `ProgramSchedule` and the legacy schedule
mapping are untouched by this phase, and the target semantic path is wired *beside* them rather than
into them. The architecture gate asserts both halves: the payload path has no path into
`ProgramSchedule`, and the three legacy files name neither the semantic repository nor its DAO.

## Read-back rules

`TargetScheduleOccurrenceRepository.occurrenceOf` reconstructs
`PlannedOccurrence(occurrenceKey, plannedFor, components)` from stored fields only:

* component order preserved (the DAO orders by the stored `position`);
* every component field preserved exactly;
* the occurrence key never parsed;
* `ruleId`/`workoutId` never derived from a `ProgramDayId`, position, name, date, weekday, index or
  slot id;
* no `"legacy"`-style placeholder ever constructed;
* `ProgramSchedule` and the legacy scheduler never consulted;
* the persisted slot is used only to *locate* the record by `(programId, targetOccurrenceKey)` — the
  read-back takes no component from a slot, because a slot has none.

## Verification

* `TargetScheduleOccurrenceRepositoryTest` — 17 cases: round trip, order, idempotency, the three
  refusal shapes, per-Program independence, key opacity, placeholder refusal, the legacy-slot case,
  re-parent refusal, the two cascade directions, and a mid-unit failure.
* `TargetScheduleOccurrenceAtomicityTest` — 12 cases through the Stage 10 boundary, including the
  rollback of both halves and the "a slot already existing does not license rewriting the stored
  occurrence" case a later revision path depends on.
* `TargetOccurrencePersistenceArchitectureTest` — the nine boundary gates.
* `scripts/program-stage14-red-mutations.sh` — 16 mutations, all caught, source restored
  byte-identically.
