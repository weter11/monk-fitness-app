# Program System — the lifecycle and My Programs (PR 5)

Scope: the **use-case layer** for a saved Program's life — activate/select, start, pause, resume,
finish, archive/unarchive, rename, edit the entry point, copy, delete — and the **My Programs** read
surface that exposes saved Programs together with their lifecycle and selection state.

Reference architecture: `docs/Monk Fitness — Program System Implementation Blueprint.MD` (cited below
as **§N**). Implementation order: §30 step 5, *Program lifecycle + My Programs*. Companions:
`docs/PROGRAM_DOMAIN_FOUNDATION.md` (PR 1), `docs/PROGRAM_ROOM_SCHEMA.md` (PR 2),
`docs/PROGRAM_SCHEDULE_FREQUENCY_CORRECTION.md` (PR 2.1), `docs/PROGRAM_DATA_ACCESS.md` (PR 3),
`docs/PROGRAM_COMPOSITION_ROOT.md` (PR 4).

This change is **decisions and reads**. No scheduler, no session runtime, no adaptive policy, no
generator, no import/export, no legacy removal — §30 steps 6+ own those.

---

## 1. The graph

```text
AppContainer                                   ← PR 4, unchanged apart from one construction
    ↓
    ProgramLifecycleService                    ← this PR
        ├── ProgramRepository                  ┐
        ├── ProgramPlanRepository              │
        ├── ProgramScheduleRepository          │ PR 3, persistence only
        ├── WorkoutSessionRepository           │
        ├── AppStateRepository                 ┘
        ├── clock: Clock          ← injected, the only source of a timestamp
        ├── idGenerator: IdGenerator           ← the only source of a new identity
        ├── standardProgramId                  ← the §3 technical fallback
        └── inTransaction                     ← the database's own transaction
    ↓
    ProgramLifecyclePolicy  (pure domain)      ← the transition table, no I/O types
    MyPrograms              (pure domain)      ← the read projection
    Program                 (pure domain)      ← `applying()`, the value edit
    ↓
ViewModels (not yet wired by this PR)
```

| file | layer | responsibility |
| --- | --- | --- |
| `domain/program/ProgramLifecyclePolicy.kt` | pure domain | the §3 transition table (`NOT_STARTED → RUNNING ↔ PAUSED → COMPLETED`), the archive decision, and `Program.applying()` — the value edit that changes only the lifecycle and its stamps |
| `domain/program/MyPrograms.kt` | pure domain | the §21/§22 read projection: a `ProgramRow` per saved Program carrying the global selection and the open-pause fact |
| `domain/program/ProgramOperationResult.kt` | pure domain | §28's `Success / Refused / Failure` and the typed `ProgramOperationRefusal` cases |
| `domain/program/StandardProgram.kt` | pure domain | the stable identity of the built-in Program (§4) and its contract |
| `domain/usecase/ProgramLifecycleService.kt` | use case | the operations: decide, refuse, persist, read. Policy above repositories, persistence inside them |
| `di/AppContainer.kt` | composition root | one construction added; nothing else |

## 2. Where the lifecycle decision lives

**Above the repositories, never inside them.** This is the load-bearing architectural rule of the
change, and it is the one a naiver implementation inverts.

`ProgramLifecyclePolicy` is an `internal object` in the pure `domain/program` package. It imports no
repository, no DAO, no Room, no Android — it is verified as foundation by
`ProgramDomainPurityTest`, which fails the build if any of those appear. Its decision is a function
of three facts:

```kotlin
fun decision(
    current: LifecycleStatus,
    transition: ProgramTransition,
    openPause: Boolean,
    archived: Boolean
): ProgramTransitionResult
```

A status, an operation, whether a pause interval is in effect, and whether the Program is archived.
That is the whole input. **The table is keyed on the operation, not on the target status** —
`START` and `RESUME` both reach `RUNNING`, and only the operation distinguishes them: `START` is
legal only from `NOT_STARTED` and stamps `actualStartDate`; `RESUME` is legal only from `PAUSED` and
stamps nothing. Keying on the target status lets `START` fire from `PAUSED` and overwrite the
factual start timestamp. That bug existed in an intermediate revision of this file and was caught by
`ProgramLifecycleTest`.

Three outcomes, because §28 says expected states are results:

* `Allowed` — the transition may be applied;
* `AlreadyThere` — the Program is already where the operation would put it. An idempotent no-op,
  not an error: it succeeds and rewrites nothing;
* `Refused` — illegal, with the reason named.

**`Program.applying()` is the only edit to the Program's own fields.** It is a `copy` on the value;
`currentRevisionId` is deliberately *not* in it. Any operation that reaches the plan must go through
`ProgramPlanRepository.saveNewRevision`, which is the immutable-revision mechanism from PR 1.

The repository layer does not decide anything. `ProgramRepository.updateProgram` writes a Program
row; it has no opinion about whether the write is legal. This is deliberate: a guard placed in a DAO
to "reduce code" would make the rule untestable without a database, and would leave the policy split
between two layers that the architecture keeps apart.

## 3. The rules, and where each is enforced

| §30 rule | Enforcement |
| --- | --- |
| Planned start date never starts a Program | Nothing in the service reads `plannedStartDate` to advance a lifecycle. `setPlannedStartDate` writes the column and returns; `START` is the only thing that moves `NOT_STARTED → RUNNING`. `applying(START)` does not consult the date either. |
| `actualStartDate` is the factual start timestamp | Set by `applying(START)` alone, from the injected clock. `PAUSE`, `RESUME`, `COMPLETE`, archive and rename never touch it. |
| `PAUSED` freezes active program time and missed-opportunity logic | A pause is an *interval*: `PAUSE` opens a `ProgramPause` row, `RESUME`/`COMPLETE` close it. The interval — not a flag — is what "frozen" means; the missed-opportunity computation is a later stage's and reads the closed interval. |
| `COMPLETED` is terminal | The table has no transition out of `COMPLETED`. `RESUME` from `COMPLETED` is `Refused`, and the status guard is evaluated *before* the pause guard so the refusal names the terminal rule rather than the missing pause. |
| Archive is not a lifecycle state | `ARCHIVE`/`UNARCHIVE` have their own decision path (`archiveDecision`) that never consults the transition table. An archived Program keeps the lifecycle it reached; `archivedAt` is a separate stamp. |
| Selected Program is global `AppState`, never a Program flag | `Program` carries no selection field. The selection lives in the one `AppState` row; `MyPrograms` joins it at read time. |
| Only one selected Program at a time | The state is one row with one pointer; `selectProgram` replaces it. `MyPrograms.selectionIsPresent` says out loud when the pointer and the rows disagree, rather than letting them silently diverge. |
| Deleting the selected Program selects the Standard fallback | `moveSelectionToStandard` runs *before* the delete. A missing Standard Program raises `StandardProgramNotSeeded` — the fallback is a rule, and a silently-absent fallback is §33's "turn a failure into a success". |
| Archiving the selected Program requires another selection | `guardSelectionForArchive` refuses before the archive; the selection is not cleared to make it pass. |
| Standard Program: selectable, copyable, shareable; not editable or deletable | `guardEditable` throws `StandardProgramProtected` for content changes; `deleteProgram` refuses outright. Selection and `copyProgram` bypass the guard, being the three things §4 permits. |
| Editing Standard means creating a user copy first | The UI's edit path calls `copyProgram` then edits the copy; `copyProgram` forces `source = ProgramSource.USER` and mints fresh ids for the whole plan. |
| No deletion while an `IN_PROGRESS` session exists | `guardDeletable` loads the sessions and refuses; the delete order is Standard guard → session guard → move selection → cascade, so the schema's `ON DELETE NO ACTION` holds. |
| No silent selection clearing for a forbidden delete | Every refusal returns before any write. No code path clears the selection to make a delete succeed. |
| Next Program / auto-start is explicit | `configureNextProgram(nextProgramId, autoStart)` sets both facts; `autoStart` defaults to off and never resumes a paused Program — auto-start starts, it does not resume. |
| Lifecycle/selection/rename/archive/planned-start create no Revision | These call `ProgramRepository.updateProgram`, never `ProgramPlanRepository.saveNewRevision`. Only structural edits reach the revision mechanism. |

## 4. My Programs

`MyPrograms.from(programs, state, openPauses)` is the one join in the change, and it has to live above
the repositories because **a `Program` cannot answer either question it adds**: it carries no
selection flag (§3) and no pause state. The projection is a value, so "only one Program is selected"
is a property of the value rather than a convention each consumer re-derives.

`ProgramRow` exposes the facts the screen renders — name, source, lifecycle, `hasStarted`,
`isCompleted`, `isArchived`, `hasOpenPause`, `isSelected` — and `MyPrograms` adds
`selectedProgramId`, `selectionIsPresent` and `hasNextProgram`. A read failure is a `Failure`, never
an empty list (§33).

## 5. What this change does not do

The Standard Program's *plan* is not built here. §4's contract is recorded in `StandardProgram.kt`
and its identity is stable, but the exercise library, the slot structure and the seeding belong to the
stage that owns generation. A missing Standard Program is a loud `StandardProgramNotSeeded`, not an
auto-created one.

No ViewModel is wired. The service is constructed in `AppContainer` and fully tested through the
production DAOs, but the UI integration is a later step.

## 6. Verification

`ProgramLifecycleTest` (26 tests) drives the real `AppContainer` over real Room/SQLite doubles — not
fakes — so the repositories, the transaction, the clock and the id generator are the production
objects. `ProgramLifecyclePolicyTest` (13 tests) exercises the pure table.

Baseline before this change: **1815 tests, 0 failures**. After: **1854 tests, 0 failures**.

```text
:app:testDebugUnitTest --rerun-tasks          1854 tests, 0 failures, 0 errors
:app:compileDebugKotlin                       BUILD SUCCESSFUL
:app:compileReleaseKotlin                     BUILD SUCCESSFUL
:app:assembleDebug -x lintVitalRelease        BUILD SUCCESSFUL
```

(`lintVitalRelease` is excluded for the known pre-existing `themes.xml` resource cycle; the release
evidence is `compileReleaseKotlin` plus the assembled debug APK.)

### RED evidence

`scripts/program-lifecycle-red-mutations.sh` applies one mutation at a time to the *production*
sources, reruns the focused suite, and restores the file. A rule is only proven if breaking it fails
a test. Twelve rules plus a control:

| mutation | caught |
| --- | --- |
| `START` legal from any status (a planned date could start a Program) | yes |
| `RESUME` legal from `COMPLETED` (terminal broken) | yes |
| `PAUSE` overwrites `actualStartDate` | yes |
| archiving moves the lifecycle | yes |
| select keeps the old selection (mutual exclusivity broken) | yes |
| delete clears the selection instead of the Standard fallback | yes |
| delete allowed with an `IN_PROGRESS` session | yes |
| the built-in Program is deletable | yes |
| the built-in Program is directly editable | yes |
| a pause creates a revision | yes |
| a copy keeps the built-in source | yes |
| a copy reuses the source's day ids | yes |
| control: unmutated tree stays GREEN | yes |

Four of the mutations found real bugs during development rather than merely confirming the guards —
the `START`/`RESUME` conflation, the guard-order message, the copy that reused plan ids, and the
delete-of-a-missing-Program that reported success. Each was a defect in this PR's own code, found by
a test before it shipped.

## 7. Base

This branch is based on `feat/program-composition-root` (PR 4), because the composition root it
wires into is not yet on `main`. Once PR 4 merges, this rebases onto it cleanly.
