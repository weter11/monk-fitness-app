# Stage 18 — the explicit, persisted target schedule source

## What this stage is

The target scheduling contour has a complete chain today:

```text
TargetSchedule
→ TargetScheduleResolver
→ TargetOccurrenceComposer
→ TargetOccurrenceReconciler
→ TargetPlanner
→ TargetSchedulePolicy
→ TargetOccurrencePresenter
→ target slot persistence
→ target occurrence persistence
→ target execution read-back
→ target execution policy
→ TargetExistingOccurrence
→ TargetScheduleOrchestrator
```

What it lacked was an **explicit source of target scheduling semantics** — a persisted, revision-owned
place from which a production caller could honestly construct `TargetScheduleInput`. This stage adds
that source.

**It does not perform the legacy/target cutover.** `ProgramScheduler` remains production scheduling's
owner and the target orchestrator remains a separately callable contour.

---

## Why `ProgramSchedule` is not the target source

A revision's legacy `ProgramSchedule` states *when its slots fall*. Two forms exist
(`FixedWeekdays(weekdays)`, `FlexiblePerWeek(sessionsPerWeek)`) and between them they answer one
question: *is this date a training date?*

A target rule states something strictly wider:

```kotlin
ruleId: String
workoutId: String
cadence: ScheduleCadence
anchorDate: LocalDate
```

and, for a derived rule, the identity of the rule it derives from. Three of those four — the rule
identity, the workout identity and the anchor date — **have no counterpart in the legacy vocabulary at
all**. Any mapper producing them would have to invent them.

The same holds for every other Program fact a mapper might reach for:

| Fact | What it actually is | Why it cannot be a target identity |
| --- | --- | --- |
| `ProgramDay.position` | an ordering | an order is not an identity |
| `ProgramDay.name` | a user label | a label is not an identity |
| `ProgramDayId` text | an opaque token | §1: never split, trimmed or parsed |
| `revisionNumber` | a 1-based ordinal | unique only *within* a Program |
| a date | a calendar fact | a date is not a rule |
| an existing slot row | a *resolved* occurrence | a result is not the configuration that produced it |

A legacy value is not a target value merely because both happen to describe timing. There is therefore
**no `ProgramSchedule → TargetSchedule` mapping** in this codebase, and the architecture gate
`TargetScheduleSourceArchitectureTest.noProductionSourceMapsALegacyScheduleOntoATargetSchedule`
asserts that absence over the whole production tree: a file that names a legacy schedule *and*
constructs a target rule fails.

---

## Ownership: the immutable revision

§6 makes a revision immutable — a structural change saves a new revision. The target semantics a
revision states are part of that saved plan, so the source is owned by `revisionId`:

```text
ProgramRevision (immutable)
└── explicit target schedule source
    ├── program_target_schedule_rule       (revisionId, ruleId)  ← primary key
    └── program_target_program_day_binding (revisionId, workoutId) ← primary key
```

Two decisions worth stating explicitly:

**Dedicated tables, not columns on `program_revision`.** A revision states a *list* of rules and a
*list* of bindings, and both their order and their membership are part of what is stored. A set of
columns could hold one rule, or one rule behind a delimiter a read would then have to parse — the exact
shape §30 step 14 already rejected for an occurrence's components.

**Composite primary keys, not surrogate ids.** `(revisionId, ruleId)` *is* the identity of a rule. That
is what lets two revisions state the same rule identity independently, stops one revision stating a
rule twice, and makes a stored row impossible to re-point at another revision by a write.

---

## The persisted rule vocabulary

```text
program_target_schedule_rule
  revisionId              TEXT     the immutable revision that states it   } membership
  ruleId                  TEXT     the rule's own identity                } identity,
  workoutId               TEXT     the workout the rule produces          }  and exactly it
  cadenceType             TEXT     DAILY | EVERY_N_DAYS | SESSIONS_PER_WEEK
                                     | FIXED_WEEKDAYS | DERIVED_EXCLUDING
  cadenceDays             INTEGER  the interval, for EVERY_N_DAYS only
  cadenceSessionsPerWeek  INTEGER  the frequency, for SESSIONS_PER_WEEK only
  cadenceWeekdays         TEXT     the named days, for FIXED_WEEKDAYS only
  cadenceSourceRuleId     TEXT     the named source rule, for DERIVED_EXCLUDING only
  anchorDate              TEXT     the date the rule is anchored to, YYYY-MM-DD
```

`cadenceType` tokens are the domain subtypes' own names in enum convention, exactly as
`program_revision.scheduleType` already does — one vocabulary for the database and the domain, so a
token cannot be right for storage and wrong for a file.

### Why a discriminator plus one payload column per form

Three reasons, each a way of losing something:

* **The payload is a field.** Reading `cadenceDays` back is a column fetch, not a delimiter split.
* **Absent stays absent.** `DAILY` carries no payload, and a form whose payload column is `NULL` cannot
  be confused with a form whose payload happens to be zero.
* **No form can be normalized into another.** `SessionsPerWeek(3)` stays a frequency of three and
  cannot become a three-day `FixedWeekdays` set or an `EveryNDays(3)` interval on the way through.

The entity's own guard holds the discriminator and its payload consistent, so the self-contradicting
row is not merely *reported* on read — it is **unrepresentable at all**:

```kotlin
when (cadenceType) {
    DAILY             -> requireNoCadencePayload("DAILY")
    EVERY_N_DAYS      -> requireOnlyPayload(EVERY_N_DAYS, present = cadenceDays != null, absent = …)
    …
}
```

This is the same discipline `program_revision` uses for `scheduleType`/`scheduleWeekdays` and
`durationType`/`durationDays`.

### Exact round-trip, all five forms

| Form | Stored as | Read back as |
| --- | --- | --- |
| `Daily` | `cadenceType=DAILY`, all payloads `NULL` | `ScheduleCadence.Daily` |
| `EveryNDays(n)` | `cadenceType=EVERY_N_DAYS`, `cadenceDays=n` | `ScheduleCadence.EveryNDays(n)` |
| `SessionsPerWeek(n)` | `cadenceType=SESSIONS_PER_WEEK`, `cadenceSessionsPerWeek=n` | `ScheduleCadence.SessionsPerWeek(n)` |
| `FixedWeekdays(days)` | `cadenceType=FIXED_WEEKDAYS`, `cadenceWeekdays="MONDAY,WEDNESDAY"` | `ScheduleCadence.FixedWeekdays(days)` |
| `DerivedExcluding(src)` | `cadenceType=DERIVED_EXCLUDING`, `cadenceSourceRuleId=src` | `ScheduleCadence.DerivedExcluding(src)` |

The read dispatches on the **stored discriminator** (`when (cadenceType)`) and each branch reads the
one column that form owns. There is no payload sniffing and no default form.

`cadenceWeekdays` is a **canonical** `TEXT` value — ascending ISO order, Monday first. A `Set<DayOfWeek>`
has no order of its own, so without a canonical stored form two equal cadences would write different
bytes and the immutability comparison would be a coin flip. This reuses the schema's existing
`program_revision.scheduleWeekdays` convention rather than introducing a second one.

---

## Explicit `workoutId → ProgramDayId` bindings

`TargetOccurrencePresenter` requires that mapping to be *stated*: a `workoutId` cannot be resolved to a
plan day from a position, a name, a date, a weekday or an id's own text. This phase gives the statement
a revision-owned home:

```text
program_target_program_day_binding
  revisionId   TEXT     the immutable revision that states it
  workoutId    TEXT     the target workout identity
  programDayId TEXT     the plan day this workout presents
```

* `(revisionId, workoutId)` is the primary key — one revision states at most one binding per workout,
  so which plan day a workout presents is a fact rather than a preference.
* `programDayId` cascades with its plan day, and `revisionId` cascades with its revision.
* `programDayId` is a foreign key **outside** the primary key, so it carries its own index
  (`index_program_target_program_day_binding_programDayId`) — the same reason `program_workout_slot`
  indexes its three foreign-key columns. Without it SQLite full-scans the binding table on every
  `program_day` modification, which the cascade performs. `revisionId` needs no index of its own on
  either table: it is the primary key's leading column, and it is the only column either is read by.
* **A foreign key cannot say the day belongs to *this* revision** — a day of another revision is a
  perfectly valid `program_day` row. The repository therefore checks the named day against the
  revision's own days before it writes, and refuses with
  `TargetScheduleSourceException.ProgramDayOutsideRevision`. Storing such a binding would present one
  revision's target schedule against another revision's plan.

The existing `TargetOccurrencePresenter` contract is unchanged; this only makes its input authorable.

---

## Typed `Source / Missing / Malformed`

```kotlin
sealed interface TargetScheduleSourceRead {
    data class Source(val source: TargetScheduleSource) : TargetScheduleSourceRead
    data class Missing(val revisionId: RevisionId) : TargetScheduleSourceRead
    data class Malformed(val revisionId: RevisionId, val reason: String) : TargetScheduleSourceRead
}
```

Three cases, kept apart on purpose:

* **`Source`** — the revision states an explicit source.
* **`Missing`** — it states none. This includes every revision saved before this existed.
* **`Malformed`** — rows exist but do not read back as a valid source (an unknown `cadenceType`, a
  payload column that contradicts its discriminator, a non-ISO `anchorDate`).

Collapsing *missing* into an empty `Source` would make "this revision states no target semantics" and
"this revision states that it has none" the same value — and only the first is true. A revision with no
rules cannot produce an occurrence at all, so an empty rule list is **not** a valid source. An invalid
stored cadence is reported rather than defaulted, because a default would change what the revision
means.

---

## Refusal before write

Every claim the source cannot honestly hold is refused **before anything reaches the database**, so a
refused source leaves no partial row behind:

| Refusal | Why it is a refusal and not a repair |
| --- | --- |
| `BlankRuleIdentity` | a rule with no identity could never have an occurrence attributed to it |
| `BlankWorkoutIdentity` | a rule that names no workout produces nothing |
| `DuplicateRuleIdentity` | two claims about one rule id cannot both be true; merging them is a scheduling decision |
| `DuplicateWorkoutBinding` | which plan day a workout presents would be undecided |
| `ProgramDayOutsideRevision` | the day belongs to another revision's plan |
| `ConflictingStoredSource` | a saved revision is immutable (§6) |
| `UnreadableStoredSource` | stored rows disagree with the vocabulary |

The duplicate checks run *before* the insert rather than being left to the primary key, because the key
refuses a duplicate **identity** while these refuse a duplicate **claim** — and the second has to be
reported as a typed refusal, not as a constraint violation.

## Immutable after save

A revision's source is written once:

* an **identical** repeat writes nothing at all;
* a **different** source for a revision that already has one is refused with
  `ConflictingStoredSource`, and the stored source survives byte-for-byte;
* the DAO declares **no `UPDATE` and no `DELETE`** — two inserts and two revision-keyed reads. There is
  no table-wide mutation path to reach, so immutability is a structural fact rather than a convention.

---

## The migration: no backfill

`MIGRATION_14_15` creates exactly two tables, adds the one index described above, and executes **no**
`UPDATE`, `INSERT`, `DELETE`, `ALTER` or `RENAME`. There is deliberately **no backfill** from `program_revision.scheduleType`,
`scheduleWeekdays` or `scheduleSessionsPerWeek`: those columns say when legacy slots fall, and any row
manufactured from them would have to invent a rule identity, a workout identity, an anchor date and a
derived rule's source. An invented row that later reads back as *stored* is worse than an absent one.

**Old revisions therefore remain without an explicit target source after the upgrade, and a read
reports that as a typed absence.** That is the truthful outcome:

```text
old revision  =  old revision  +  no invented target schedule
```

Verified on a real SQLite engine in `ProgramMigrationPreservationTest`: a populated version-7 database
is migrated through the whole chain, every legacy row survives byte-for-byte, and the two new tables
arrive empty.

---

## The bridge into `TargetScheduleInput`

`TargetScheduleSourceBridge` is the smallest explicit bridge from the persisted source to the two lists
a caller cannot honestly reconstruct:

```text
persisted, revision-owned target source
        ↓  TargetScheduleSourceBridge
scheduleDefinitions + programDayBindings
        ↓  TargetScheduleInput (the caller's other eight values)
TargetScheduleInputAdapter
        ↓
TargetScheduleOrchestrationRequest
```

The bridge decides nothing. It does not read `ProgramSchedule` and translate it; does not construct a
target identity from a `ProgramDay`, a position, a name, a date or an id's own text — it constructs no
identity at all, it forwards stored ones; does not decide a date, window, composition, `asOf`, pause or
resolved source (those are the caller's other eight input values); does not call the planner, policy,
presenter, persister or orchestrator; and inspects no execution, session or performance state. It holds
no clock and no identity generator.

**A missing source is refused, never filled in.** `definitionsAndBindingsOf` returns
`TargetScheduleSourceRead.Missing` unchanged rather than an empty pair of lists, and there is no legacy
fallback to take instead.

`TargetScheduleSourceIntegrationTest` proves the whole chain end to end on a real SQLite engine —
persist, read back, build `TargetScheduleInput`, the *existing* `TargetScheduleInputAdapter`,
`TargetScheduleOrchestrationRequest` — and separately resolves the resulting schedules to pin that a
stored `SessionsPerWeek(3)` still means MON/WED/FRI and not an every-third-day progression. The pass
is deliberately **not** run: producing a request is the last step, because wiring the suite to the
orchestrator would perform the cutover.

---

## Import/export: an explicit ownership decision

**The transfer format was intentionally not extended in this phase.** The reasoning, recorded here so
it is a decision rather than an omission:

1. **No current authoring path produces a target schedule source.** The editor, the generator, the
   Standard Program and the importer all build `ProgramSchedule` values; none of them can state a rule
   identity, a workout identity, an anchor date or a derived rule's source, and §7/P13 forbid them
   from inventing those.
2. **The current importer therefore cannot legitimately create a Program containing target source it
   does not have.** A transfer field for target definitions would have to be either omitted — producing
   a Program with no target source, which reads as a typed `Missing` and is honest — or populated by
   inference from `ProgramSchedule`, which is exactly the `ProgramSchedule → TargetSchedule` mapping
   this phase exists to prevent.
3. **No target source is inferred from legacy `ProgramSchedule`, and none is backfilled for old
   revisions**, in the transfer path or anywhere else.
4. **The transfer extension belongs to the later authoring/integration phase** — the phase that gives
   target source an authoring path of its own. Until then, there is nothing to export.
5. **Once such source becomes authorable and exportable, the transfer contract must be extended
   explicitly**, adding a documented field rather than interpreting `ProgramSchedule` — and the
   extension must preserve the existing §2 contract unchanged: mandatory `formatVersion`, and no
   exported runtime, history or statistics, no `programId`, no active flag, current day or cycle, no
   session state and no adaptive history.

The existing `ProgramTransferArchitectureTest` suite is untouched and green, which is the correct
statement that the transfer model was **not** silently widened.

---

## What remains deferred

* **The production cutover.** `ProgramScheduler` remains the production scheduler.
  `TargetScheduleOrchestrator` is not connected to production scheduling, and the bridge is wired but
  consumed by nobody — asserted mechanically by
  `TargetScheduleSourceArchitectureTest.theTargetContourStaysASeparatelyCallableContourAndNothingWasCutover`,
  which fails if any production file outside the composition root constructs the bridge.
* **Authoring target source.** No UI, editor or generator path can yet state one. That is the
  precondition for the transfer extension above.
* **Reading the source into a running pass.** The bridge returns the facts; the future caller that
  composes the other eight `TargetScheduleInput` values does not exist yet.

---

## Architecture gates

`TargetScheduleSourceArchitectureTest` states twelve mechanical claims against the sources themselves
with comments stripped first, so a KDoc may *say* "never reads `ProgramSchedule`" without tripping the
rule about reading it:

1. no scheduler and no legacy schedule vocabulary anywhere on the source path;
2. the cadence is read form-first — no payload sniffing, no normalization between forms;
3. no target identity derived from a plan day, position, name, id text or date;
4. no dependency on `ExistingOccurrence` or `ActualResult`;
5. no dependency on session, runtime or performance state;
6. the domain value holds no repository, DAO, entity, clock, generator or UI state;
7. input construction consumes the explicit persisted source;
8. a missing source is never converted into an empty valid source;
9. a saved revision's source gains no update and no delete path;
10. every target identity is stored in its own column and stays opaque;
11. the closed production-reference lists stay closed;
12. the target contour stays separately callable and nothing was cut over.

---

## Verification

All figures below are measured on the final tree of this stage, with `--rerun-tasks`.

| Gate | Result |
| --- | --- |
| `:app:testDebugUnitTest` | **2643 tests, 0 failures, 0 errors, 0 skipped** |
| `@Test` annotation count | **2643** (matches the executed count exactly) |
| `@Test`-bearing files | 304 |
| test classes (result files) | 282 |
| Focused Stage 18 suites | **52 tests, 0 failures** |
| `:app:compileDebugKotlin` | green |
| `:app:compileDebugUnitTestKotlin` | green |
| `:app:compileReleaseKotlin` | green |
| `:app:compileReleaseJavaWithJavac` | green |
| `:app:assembleDebug` | green |
| `git diff --check` | clean |
| RED mutations | see below |

The focused Stage 18 suites are `TargetScheduleSourceRepositoryTest`,
`TargetScheduleSourceIntegrationTest` and `TargetScheduleSourceArchitectureTest`.

Baseline for comparison: `main` carried 2511 tests before this stage.

### Architecture assertions revised

Seven existing closed-list assertions grew, each by exactly the Stage 18 items and each keeping its
legacy-negative half intact:

| Suite | Assertion | Change |
| --- | --- | --- |
| `ProgramSchemaTest` | entity set, version chain, statement pool | +2 entities, version 15, `MIGRATION_14_15` statements added to `statementFor` |
| `AdaptivePersistenceSchemaTest` | declared version, entity list, registered migrations | 14 → 15, +2 entities, +1 registered step |
| `ProgramOwnershipCascadeTest` | survivor identities, owned-table count | +2 survivor rows, 15 → 17 owned tables |
| `ProgramDataAccessArchitectureTest` | harness entity→table map | +2 tables (17 → 19) |
| `AppContainerTest` | DAO accessors, repository map | +1 accessor, +1 repository |
| `CompositionRootArchitectureTest` | target accessor list, accessor count | +1 accessor, 17 → 18 |
| `ProgramMigrationPreservationTest` | chain version assertions | +`MIGRATION_14_15` |

No assertion was replaced by a broad predicate, and none was removed.

One genuine **coverage gap** was found and fixed rather than accommodated: the shared
`ProgramGraphInserts` fixture never inserted a target-source row, so the ownership cascade test was
silently exercising empty tables. The fixture now inserts both halves of a source (with an
`EVERY_N_DAYS` rule, so the payload column is exercised too), and the cascade test proves a source dies
with its Program exactly as a slot does.

### RED mutation evidence

`scripts/program-stage18-red-mutations.sh` — **15 mutations, caught 15, missed 0**, every mutated
production source restored byte-identically (verified by `md5sum -c` over all seven files).

The harness enforces four rules: a compile error is never scored as a catch; a comment-only mutation
proves nothing (the probe strips comments and asserts the mutated line survived as real code); a no-op
mutation is never scored as a catch (the probe asserts the file actually changed); and every mutation
preserves every public signature so the whole tree compiles and a real oracle runs.

| # | Mutation | Broken contract |
| --- | --- | --- |
| 1 | `rulesOfRevision` drops its `revisionId` predicate | revision scoping |
| 2 | rule identity read from a constant | stored rule identity |
| 3 | workout identity read from a constant | stored workout identity |
| 4 | `EveryNDays` interval altered | cadence payload fidelity |
| 5 | `SessionsPerWeek` normalized into a weekday set | cadence-form fidelity |
| 6 | `FixedWeekdays` collapsed to one day | cadence payload fidelity |
| 7 | `DerivedExcluding.sourceRuleId` read from the row's own rule | derived-rule source identity |
| 8 | `anchorDate` read from a constant | anchor fidelity |
| 9 | stored binding replaced by an inferred plan day | explicit binding ownership |
| 10 | duplicate rule identity accepted | duplicate-claim refusal |
| 11 | duplicate workout binding accepted | duplicate-claim refusal |
| 12 | missing source converted to an empty source | typed absence |
| 13 | cross-revision plan-day binding accepted | revision-scoped binding |
| 14 | target source derived from legacy `ProgramSchedule` | no legacy→target inference |
| 15 | bridge bypasses the persisted source | explicit-source consumption |
