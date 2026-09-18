# Program System — schedule-frequency correction (PR 2.1)

Scope: the **schema correction** the data-access stage reported — a `FLEXIBLE_PER_WEEK` revision could
not store the sessions-per-week frequency its schedule is defined by. This document records the evidence
that it is a defect rather than a design choice, the smallest change that closes it, what the change
proves, and what it deliberately leaves alone.

Reference architecture: `docs/Monk Fitness — Program System Implementation Blueprint.MD` (cited below as
**§N**). Companions: `docs/PROGRAM_ROOM_SCHEMA.md` (PR 2, the target schema) and
`docs/PROGRAM_DOMAIN_FOUNDATION.md` (PR 1, the domain).

This is a **schema correction only**. There is no DAO, no mapper, no repository, no scheduler, no runtime,
no UI, no adaptive change and no legacy work in it: §30 step 3 and later own all of that, and §30 step 2
is what this repairs.

---

## 1. The gap

```text
domain (PR 1, authoritative)   ProgramSchedule.FlexiblePerWeek(sessionsPerWeek: Int)   // required, 1..7
Blueprint §20                  "Fixed weekdays and deterministic flexible frequency are supported."
version-8 schema (PR 2)        program_revision: scheduleType + scheduleWeekdays      // no frequency
```

* the **domain type cannot exist without the number**: `ProgramSchedule.FlexiblePerWeek` takes
  `sessionsPerWeek` as a required field and refuses anything outside `1..7`, and the revision it belongs
  to is the immutable record of the plan (§6);
* the **Blueprint treats the schedule as revision content**: §23 lists `ProgramRevision` among the core
  entities and keeps mode, duration and schedule on the revision, and §20 states that fixed weekdays and
  a deterministic flexible frequency are both supported — a form the architecture supports is a form a
  revision must be able to describe;
* what §20 leaves to the Scheduler is the **choice** of frequency, not its storage: the Scheduler decides
  *which* dates and *how many* sessions per week a plan gets, and the decision it made is a fact about
  the revision it was made for. A revision that knew it was a flexible-frequency plan but not how many
  sessions a week it holds would not describe the plan it is the record of;
* consequently the PR-2 comment "the frequency is chosen by the Scheduler and is not stored here"
  described the *choosing* correctly and the *storing* incorrectly, and `MIGRATION_7_8` created a table
  in which a flexible-frequency revision could not be written or read without losing the number or
  inventing one. The data-access stage (PR 3) found this and refused the form in both directions rather
  than dropping or defaulting it, and reported it as a blocker.

### Why the alternatives are not acceptable

| alternative | why not |
| --- | --- |
| derive the frequency from the generated slots | the slots are scheduled *from* the frequency: at the moment a revision is saved there are no slots yet, and a weekly count read back out of dates would be a guess about a plan, not the plan |
| default it (e.g. `3`) | states a weekly frequency the user never chose, for a plan whose whole purpose is to be deterministic about exactly that number |
| make the column `NOT NULL` | the fixed-weekday form has no frequency: `NOT NULL` would either reject a legal revision or force a meaningless value into the other form |
| store it outside the revision (settings, an editor draft) | the schedule is revision content (§6): a structural change — including a frequency change — is a *new revision*, and the previous revision must keep describing the schedule it ran at |
| leave it as it is | `FLEXIBLE_PER_WEEK` is then a schedule form the app can neither save nor load, which is a hole in a schema the later stages build on |

## 2. The minimal change

```text
ProgramRevisionEntity    + scheduleSessionsPerWeek: Int?   (declared last: the column is appended)
                         + the discriminator guard the duration column already has
AppDatabase              version 8 → 9, MIGRATION_8_9 registered (one ALTER TABLE ... ADD COLUMN)
```

* **one nullable column, no default.** Only one of the two schedule forms has a frequency, so the column
  is nullable; and it carries no default, because a default would put a weekly frequency on rows whose
  schedule is a fixed weekday set and therefore has none.
* **the pair is guarded like `durationType`/`durationDays`.** `FIXED_WEEKDAYS` must store no frequency,
  `FLEXIBLE_PER_WEEK` must store one within `1..7`. The range is the domain's own range — pinned by a test
  that derives the accepted set from the domain type and compares it with the entity's — rather than a
  second decision taken in the data layer.
* **the version-8 migration is untouched.** Editing `MIGRATION_7_8` to create the column would leave every
  device already at version 8 with a table Room validates and rejects: Room runs no migration for a
  version it is already at. The correction is therefore a new step, and the chain a device runs is
  `7 → 8 → 9`.
* **the column is declared last in the entity** so the declaration order equals the physical order: an
  `ALTER TABLE ... ADD COLUMN` appends, and a freshly created database must hold the same table
  definition as an upgraded one — asserted by comparing `sqlite_master` against the DDL Room generates
  for the current entity set.

## 3. Files changed

| file | change |
| --- | --- |
| `data/model/ProgramRevisionEntity.kt` | the `scheduleSessionsPerWeek` property, its `@property` KDoc, the corrected schedule note, and the discriminator guard |
| `data/local/AppDatabase.kt` | `version = 9`, `MIGRATION_8_9` (`ALTER TABLE \`program_revision\` ADD COLUMN \`scheduleSessionsPerWeek\` INTEGER`), registration |
| `ProgramSchemaFixture.kt` (test) | `ADDED_COLUMNS`, `EXPECTED_ADDITIVE_STATEMENTS`, `columnsNow`, and a current-version `CREATE TABLE` expectation beside the version-8 one |
| `ProgramSchemaTest.kt` (test) | the version/chain test, per-statement column coverage, the additive-migration statements, the nullable/no-default pin, the engine round trip, the domain-range equivalence, and the extended discriminator guard |
| `ProgramMigrationPreservationTest.kt` (test) | the chain becomes `7 → 8 → 9`; a new test upgrades a **populated version-8** database and asserts nothing is invented |
| `ProgramOwnershipCascadeTest.kt`, `ProgramGraphInserts.kt` (test) | the suite runs the deployed chain; the graph fixture states the frequency its flexible schedule runs at |
| `AdaptivePersistenceSchemaTest.kt` (test) | the shipped-entity census follows the current version and the registered chain |
| `docs/PROGRAM_ROOM_SCHEMA.md` | the version row and the migration description |

## 4. What the tests prove

| claim | test |
| --- | --- |
| the column exists, with `INTEGER` affinity and nullable, on a real engine after the chain | `ProgramSchemaTest.theFrequencySurvivesTheDeployedMigrationChainAndReconstructsTheDomainSchedule` |
| the stored number is the domain's `sessionsPerWeek`, for every value in the range, and the row reconstructs the same schedule | same test — a domain value is written, read back and rebuilt from the stored row |
| a fixed-weekday schedule stores **nothing** in the column (no invented frequency) | same test |
| the entity accepts exactly the frequencies the domain accepts, and refuses a missing or out-of-range one, and a frequency on the weekday form | `theEntityStoresExactlyTheFrequenciesTheDomainScheduleAccepts`, `aRevisionCannotDescribeADurationOrAScheduleItsDiscriminatorDenies` |
| the correction executes exactly the derived `ADD COLUMN` statements, one per addition, names no other table, and creates/drops/rewrites nothing | `theAdditiveMigrationAddsExactlyTheDeclaredColumnsAndNothingElse` |
| the column is nullable and carries no `DEFAULT` | `theAddedFrequencyColumnIsNullableAndInventsNoDefault` |
| the migration chain is unbroken (`1..current`), every declared migration is registered and nothing else is | `theDatabaseMovesToTheCurrentVersionThroughOneUnbrokenRegisteredChain` |
| a populated **version-8** database upgrades without changing any row it held, gains only the appended column, and leaves every other table's and index's DDL byte-identical | `ProgramMigrationPreservationTest.theCorrectionUpgradesAPopulatedVersionEightDatabaseWithoutInventingAFrequency` |
| the shipped legacy rows still survive the whole chain | the same suite's preservation tests, now running `7 → 8 → 9` |
| a freshly created schema and a migrated one agree | `theMigratedSchemaIsTheSchemaRoomExpects` compares `sqlite_master` with Room's DDL for the current entity set |

## 5. A consequence worth stating: pre-correction flexible rows have no frequency

A `FLEXIBLE_PER_WEEK` row written while the schema was at version 8 holds no frequency, and the migration
does not invent one: backfilling would mean stating a weekly frequency the user never chose. After the
correction such a row is **invalid persisted data** — the entity's discriminator guard refuses it, so the
mapper and every repository built on it refuse it too.

In practice there is no such row, and the reason is structural rather than lucky: nothing writes the
target tables yet. The version-8 schema shipped without a DAO, a repository, a mapper, a use case or any
UI (§30 step 3 and later), and the data-access stage that would have been the first writer refused the
form in both directions. The one graph fixture that carried a version-8-shaped flexible revision was a
*test* fixture, and it now states the frequency the form requires.

## 6. What this does not change

* **no domain semantics**: the domain already required `sessionsPerWeek`; nothing in `domain/` is touched.
* **no DAO, mapper or repository**: the readers and writers of the column are §30 step 3's work.
* **no scheduler behaviour**: which frequency a plan gets, which dates it lands on, and how a horizon is
  extended remain the Scheduler's (§20).
* **no other table, index, key or delete action**, and no legacy table: the correction is one appended
  column on `program_revision`.
* **no legacy or Stage-1 change**: `family_progression_state`, `adaptive_decision_record` and `set_log`
  are untouched, as they were by PR 2.

## 7. The follow-up this correction creates in the data-access stage

The data-access layer (PR 3) was written against the version-8 schema and is frozen while this correction
is reviewed. Once the correction lands it needs three small, mechanical changes, none of which this PR
performs:

1. `PlanMappers`: store `scheduleSessionsPerWeek` when writing a `FLEXIBLE_PER_WEEK` revision and read it
   back into `ProgramSchedule.FlexiblePerWeek`; the `FLEXIBLE_FREQUENCY_GAP` refusal and the two tests
   that pin it are the exit criteria of this correction, so they are *revised*, not deleted.
2. its test rig: run `MIGRATION_8_9` after `MIGRATION_7_8` so the database under test is the one a device
   opens (a version-8 database would not have the column, and the revision insert would fail).
3. nothing in its architecture test: the entity declares the appended column last, so the
   entity-field-order-equals-column-order check keeps holding after this correction.

Whether the correction lands before the data-access PR merges is an owner decision, and the two orders
have different costs:

* **correction first** (recommended): the data-access PR is the first writer of the target tables, so if
  it merges first there is a window in which a flexible-frequency revision can be neither written nor
  read; the correction is a one-column, one-migration change with no consumers to coordinate, so landing
  it first costs one rebase of the data-access PR and nothing else.
* **data-access first**: acceptable only because nothing constructs the layer yet (§30 step 4 is the
  composition root, and no UI or use case calls it), so the refusal cannot reach a user; it would then be
  a follow-up commit that revises the mapper and its two pinned tests.
