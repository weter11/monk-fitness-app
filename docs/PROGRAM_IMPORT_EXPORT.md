# §30 step 13 — Program Import / Export / Share

This document is the record of one stage of the Program System roadmap
(`docs/Monk Fitness — Program System Implementation Blueprint.MD` §30 step 13): making a Program
**portable** — exporting its definition to a file, importing that file as a **new independent Program**, and
sharing the file through the platform.

```text
Program
  ↕
Export DTO / JSON          domain/program/transfer
  ↕
shared file                the Android Share Sheet boundary
  ↕
Import validation          parse → formatVersion → schema → exerciseId → semantic → Import Draft
  ↕
Import Draft               the editor's own draft, reviewed before anything is written
  ↕
new Program                §27's creation unit: Program + Revision + plan + initial opportunities
```

The stage's scope rules are §2's: it exports the Program's **definition and configuration** and nothing
else, and it stays independent of Session history, Progress/History analytics, the adaptive state and
decisions, the legacy Stage-1 adaptive storage, and §30 step 14's UI navigation work.

---

## 1. What is new, by layer

| layer | files | what it owns |
| --- | --- | --- |
| **the transfer model** | `domain/program/transfer/ProgramTransferDocument.kt` | the DTO hierarchy: the allowlist, written out |
| **the codec** | `.../Json.kt`, `.../ProgramTransferJson.kt`, `.../ProgramTransferReader.kt` | the strict JSON reader, the deterministic writer, the schema layer |
| **the validation** | `.../ProgramTransferValidation.kt`, `.../ProgramTransferIssue.kt` | §6's semantic rules, as findings with a place |
| **the mapping** | `.../ProgramTransferMapper.kt`, `.../ProgramImportDraft.kt` | DTO ⇄ domain, in both directions |
| **the contract** | `.../ProgramTransferFormat.kt`, `.../ProgramTransferResult.kt`, `.../ExerciseLibrary.kt` | the format's identity, §28's error mapping, §5's exerciseId boundary |
| **the use cases** | `domain/usecase/ProgramExportService.kt`, `.../ProgramImportService.kt` | §5's two halves, §8's ownership split and §27's creation unit |
| **the exercise boundary** | `domain/usecase/ProgramExerciseLibrary.kt` | §5's one question about an id |
| **the platform** | `platform/ProgramShareSheet.kt`, `platform/ProgramDocumentImport.kt`, the manifest's `FileProvider`, `res/xml/program_file_paths.xml` | `ACTION_SEND` over a `content://` URI, and the platform's document picker |
| **one Scheduler API** | `domain/usecase/ProgramScheduler.kt` | `initialSlotsFor(program, revision)`: the opportunities a Program **being created** receives |
| **the wiring** | `di/AppContainer.kt` | two nodes: `programExportService`, `programImportService` |

The one production change *outside* this stage is the Scheduler's third entry point (§11 below), which
exists because §27's creation unit needs the Scheduler's decision before anything is stored.

---

## 2. `formatVersion`

`formatVersion` is `1` (`ProgramTransferFormat.VERSION`), and it is the **only** version this reader
accepts. It is required: a document without it is a schema failure (a missing required field), a document
with another number is `ProgramTransferRejection.UnsupportedFormatVersion` — refused *before* the schema
step, because a version this reader does not know is a document whose schema it does not know either.

The policy is the one the two cases make possible:

```text
same version       read, with the schema this file states
newer version      refused, with a sentence that says so ("update the app to import it")
older version      refused the same way (there is no older version yet; the rule does not change when there is)
```

Adding a field is therefore a version bump, and that is what keeps §11's determinism a statement about
**one** known format rather than about every format that will ever exist.

## 3. The JSON schema, exactly

```json
{
  "format": "monkfitness.program",
  "formatVersion": 1,
  "program": {
    "name": "…",
    "description": "…"
  },
  "revision": {
    "mode": "MANUAL" | "GENERATED",
    "duration": { "kind": "FIXED_DAYS", "days": 30 } | { "kind": "INDEFINITE" },
    "schedule": { "kind": "FIXED_WEEKDAYS", "weekdays": ["MONDAY", "WEDNESDAY", "FRIDAY"] }
              | { "kind": "FLEXIBLE_PER_WEEK", "sessionsPerWeek": 4 },
    "focus": { "goal": "BALANCED" }
           | { "goal": "FOCUSED", "focuses": ["PULL", "CORE"] }
           | { "goal": "CUSTOM", "allocations": [ { "focus": "PUSH", "percent": 60 },
                                                  { "focus": "LEGS", "percent": 40 } ] },
    "days": [
      {
        "type": "TRAINING" | "MOBILITY" | "POSTURE_MOBILITY" | "REST",
        "name": "Push day",                     // omitted for an unnamed day
        "exercises": [
          {
            "exerciseId": "pushups",
            "prescription": { "dimension": "REP_BASED", "perSetTargets": [12, 10, 8, 6] },
            "origin": "GENERATED" | "USER_AUTHORED",
            "pinned": false
          }
        ]
      }
    ]
  }
}
```

Rules that are part of the schema rather than of a document:

* **field order is fixed** and is the order above; the writer emits exactly it (§11's determinism);
* **scalar arrays are written on one line** (`["MONDAY", "WEDNESDAY", "FRIDAY"]`, `[12, 10, 8, 6]`) and
  **arrays of records one element per line** — the writer's own rule, so formatting is a function of the
  value;
* **the file ends with exactly one newline** and is UTF-8 with no BOM;
* **an absent optional fact is an absent field**, never `null` (the reader *accepts* `null` only to refuse it
  as a wrong type where it appears);
* **a record's allowed field set depends on its own variant**: a `INDEFINITE` duration that also carries
  `days`, a `FIXED_WEEKDAYS` schedule that also carries `sessionsPerWeek`, a `BALANCED` focus that also
  carries `focuses` — each is an `UnknownField` finding, because a record that states one fact twice has no
  single reading;
* **an undefined field anywhere is refused** (`UnknownField`), which is §2's *"allowlist design, not a
  blacklist"* read as a rule of the reader.

## 4. The transferable fields — the allowlist

| field | why it is transferable |
| --- | --- |
| `program.name`, `program.description` | the Program's own definition; §6 lists them as *not* structural, which is why they live here rather than on a revision |
| `revision.mode` | §2: manual or generated is revision content |
| `revision.duration` | §20: how long the revision runs |
| `revision.schedule` | §20: the weekly rhythm the user chose |
| `revision.focus` | §8, and §6 lists *goals/focus* among what creates a revision |
| `revision.days[].type`, `.name` | what each day is and what it is called |
| `revision.days[].exercises[].exerciseId` | the Exercise Library key, opaque to the format (§10) |
| `revision.days[].exercises[].prescription` | §10: what the element asks for, **per set**, in its dimension |
| `revision.days[].exercises[].origin` | §7: who authored it, which is what a regenerate pass keeps or replaces |
| `revision.days[].exercises[].pinned` | §7: exemption from automatic change — content, not bookkeeping |

## 5. The fields intentionally **excluded**, and why

Excluded by §2, each with the reason it cannot be part of a definition:

| excluded | why |
| --- | --- |
| `programId`, `revisionId`, `programDayId`, `programExerciseId`, `revisionNumber` | identity. §3 requires an import to mint a completely new graph; a format that carried identity would make "reuse it" representable |
| `active`/`selected` state, `current day`, cycle | runtime state. §21: selection is one global fact (`AppState`), not a Program flag, and a day is not ownership (§1) |
| `lifecycleStatus`, `createdAt`, `updatedAt`, `plannedStartDate`, `actualStartDate`, `archivedAt` | lifecycle and timestamps. §18 forbids carrying them; the imported Program's own are minted from the injected clock |
| `ProgramSource` | see §6 below — it is provenance *about this app's storage*, and the importer establishes `IMPORTED` directly |
| Sessions, SetLogs, statistics, progress, history, streak state | §16. The document type has no field for any of them and the exporter holds no repository that could produce one |
| `FamilyProgressionState`, `AdaptiveDecisionRecord`, `AdaptiveAdjustment` | §15. Same argument, and the imported Program's adaptive history starts empty by construction |
| in-progress Session state, slot ids, slot status, attempts | §2: the Scheduler's own runtime facts |
| day **positions** | §6 numbers a revision's days `1..n` in the order they are held, so the array *is* the order; a position would be a second fact that could disagree with it |

## 6. `ProgramSource` is deliberately not in the format

§2 asks for this decision explicitly. It is not serialized:

1. **source is provenance, not definition.** `STANDARD`/`USER`/`IMPORTED` says who authored a Program *in
   this app's storage* — it is what §4's copy-before-edit rule derives from — not what the Program plans;
2. **the importing side must not believe it.** §5's invariant is that an imported Program is never treated as
   the original or as the built-in Standard Program. A serialized source would be a field the importer would
   always have to overwrite, and a field that always has to be distrusted;
3. **an exported Standard Program would otherwise claim to be standard**, inviting exactly the reading §10
   forbids: a copy that stays protected and therefore cannot be edited or deleted.

The importer therefore *establishes* the source: `ProgramSource.IMPORTED`, always — the alternative §9 names.

## 7. Identity reminting

An import mints, from the injected `IdGenerator` and nothing else:

```text
new ProgramId            §3, §9
new RevisionId           §3, §17 (numbered 1)
new ProgramDayId         per day, §3
new ProgramExerciseId    per occurrence, §3/§9 — the same exercise twice is two identities
```

Nothing can be reused even in principle: the format has no identity to carry (§5), the mapper's
`revisionOf` replaces the draft's working handles, and the reviewed draft names no Program. The structural
guard `require(revision.structure == plan.structure)` — the same claim §30 step 6's `mintRevision` makes —
asserts that re-identifying a plan changed nothing else about it.

## 8. The imported source semantics

| fact | value on import | why |
| --- | --- | --- |
| `source` | `IMPORTED` | §9, §10 — and the format cannot say otherwise |
| `lifecycleStatus` | `NOT_STARTED` | importing is not starting |
| `actualStartDate` | `null` | §3: only `startProgram` sets it |
| `createdAt` / `updatedAt` / revision `createdAt` | one `clock.now()` reading | §18: new factual values, never the source's |
| `plannedStartDate` | **the day of the import** | §27 requires the creation unit to include the revision's initial opportunities, and the Scheduler refuses to plan a Program with no date to plan from (deliberately: inventing one would be the Scheduler deciding when a user's program begins). The date is supplied by the layer that decides *what Program is being created*, from the injected clock and the injected calendar — a **plan**, and §3's *"a planned start date does not automatically start a Program"* holds verbatim. See §19's open decision |
| selection | unchanged unless asked | §9 — see below |
| slots | the opportunity `1..n` of the imported revision, decided by the Scheduler | §8, §27 |

## 9. The validation pipeline

§5's order, one concern per step, each with its own typed refusal:

```text
bytes                  ProgramTransferFormat.decode        UTF-8, reported rather than replaced
  ↓ parse              Json.read                            a syntax failure produces no value at all
  ↓ formatVersion      ProgramTransferReader.read           present, a whole number, supported
  ↓ transfer schema    ProgramTransferReader.read           fields, kinds, tokens, per-variant allowlists
  ↓ exerciseId         ProgramImportService + ExerciseLibrary  one yes/no per referenced id
  ↓ semantic           ProgramTransferValidation.issuesIn   the domain's own value rules, decided first
  ↓ Import Draft       ProgramTransferMapper.draftOf        the domain's draft + the domain's own validation
  ↓ save new Program   ProgramImportService.save            §27's unit, in one transaction
```

Two separations inside that are worth stating because they are the ones that make each refusal *right*:

* **schema vs semantic.** *"Is this document the shape the format defines?"* is the reader's; *"is what it
  says a program this app can hold?"* is `ProgramTransferValidation`'s. A rest day with elements, a duration
  of zero days, a custom focus that does not sum to a hundred read perfectly as schema and are refused one
  layer up;
* **the transfer's rules vs the domain's.** The transfer layer decides exactly the rules the domain's own
  constructors would **throw** on; the rules about a plan *as a whole* (a Program has a name, it has days,
  its days are numbered in order, a work day plans something) are decided by
  `ProgramDraftValidation` — the same gate `Save` consults in the editor — and arrive as
  `ProgramTransferIssue.PlanNotSavable` carrying the domain's own findings. §6 asks for that reuse, and a
  second copy of *"a REST day prescribes nothing"* would be a second rule that can drift.

The whole pipeline runs **before anything is written**: a reviewed-and-rejected import leaves no row in any
table, and an import that is reviewed and never accepted leaves none either.

## 10. The exerciseId boundary

The target domain keeps `exerciseId` opaque (`ProgramExercise` holds a library key and never resolves it,
§10), so an import cannot validate the ids it read by itself — and §5 forbids every shortcut around that:
no metadata in the file, no copying names or equipment, no foreign key, no mutation of the library.

The boundary is one port with one production implementation:

```text
domain/program/transfer/ExerciseLibrary.kt      fun interface ExerciseLibrary { suspend fun knows(exerciseId: String): Boolean }
domain/usecase/ProgramExerciseLibrary.kt        the app's catalogue, asked per id, read once
di/AppContainer.kt                              the only place it is handed to the import
```

`ProgramExerciseLibrary` sits beside the catalogue it reads (`WorkoutGenerator.getExerciseLibrary()`), keeps
the **ids** and nothing else, and is deliberately *not* §24's future `ExerciseLibraryRepository`: that
repository will be a persistence-facing read of a later schema, and inventing it here would be a second
owner of a fact this stage only needs to ask about. An unknown id refuses the whole document and names every
id it could not resolve; nothing is invented, substituted or dropped.

## 11. The import draft boundary

The pipeline's end is `ProgramImportDraft` — the parsed document together with the **domain's own**
`ProgramEditorDraft`. Reusing the editor's draft is §7's *"Create/Edit/Copy/import review"* entry point: the
draft's KDoc already names `programId = null, baseRevisionId = null` as *"create (or review an import)"*, so
the review of an import and the review of an edit are the same kind of value and a future screen needs no
second renderer.

* the draft may be inspected for everything §7 lists — name, description, mode, duration, schedule,
  goals/focus, day structure, exercise structure;
* **it acquires no persistent identity**: its day and element identities are working handles, minted from
  the injected source exactly as the editor mints its own, and `revisionOf` replaces every one of them;
* the constructor is `internal`, so a caller cannot assemble a draft that skipped validation and hand it to
  the save.

## 12. The atomic save

An accepted import creates, in **one transaction**:

```text
Program + Revision + ProgramDays + ProgramExercises + initial opportunities (+ the selection, when asked)
```

`ProgramRepository.createProgram` is the primitive that carries the unit; the importer composes the
selection move inside the same unit when `makeActive` is on. A failure at any leg rolls the whole thing back
through the database's own transaction, so a failed import leaves no partial Program, no partial revision
and no partial slot (§13, §27). The atomicity suite plants a failure at the plan-elements leg and at the
opportunities leg — the second is *after* the Program, revision and day rows were written — and asserts a
whole-database census is unchanged.

## 13. Scheduler ownership

§20 gives the Scheduler the timing decisions and §8 forbids the importer to invent them, so the composition
boundary is explicit, and it needed one small API:

```text
Scheduler.initialSlotsFor(program, revision)   the opportunities a Program *being created* receives
  · same decision      SlotPlanner.plan over the same request assembly (one private `decide`, two callers)
  · same anchor rule   actualStartDate ?: plannedStartDate, else NoSchedulingAnchor — this entry point
                       cannot be used to make the Scheduler invent a date either
  · no new semantics   a Program being created has no slots and no pauses: empty is a fact, not a guess
  · no transaction     it decides and writes nothing; the caller that owns §27's unit is the caller that writes
```

The importer calls exactly that and constructs no slot at all — the architecture suite forbids `WorkoutSlot(`
and the scheduling vocabulary (`SlotPlanner`, `SlotPlan`, `ScheduleRequest`, `ScheduleWindow`, `SlotIdSource`)
anywhere in `ProgramImportService`, so "the Scheduler owns opportunities" is a property of the shape rather
than a rule the importer remembers.

## 14. Selection: default OFF, explicit opt-in

§9's *"imported Program is NOT selected"* is the default, and the opt-in is one boolean:

```kotlin
suspend fun save(draft: ProgramImportDraft, makeActive: Boolean = false): ProgramTransferResult<Program>
```

* **OFF** — the selection is not touched at all: not cleared, not compared, not rewritten;
* **ON** — the imported Program becomes the selected one through `ProgramLifecycleService.selectProgram`,
  which is the layer that owns selection (§3, §21), inside the same transaction as the creation. The importer
  never writes `AppState`.

"Imported" and "selected" are separate facts, and the tests hold them apart in both directions: the default
leaves the previous selection exactly as it was, and the explicit choice moves it to the imported Program.

## 15. The Standard Program's import semantics

§10 requires an exported Standard Program to become an independent, user-owned import — so the tests export
the built-in Program through **the same format** (no special case) and assert that what arrives is
`IMPORTED`, is not `isBuiltIn`, and is therefore editable and deletable like any other Program the user owns.
The built-in Program's own row is untouched.

## 16. UTF-8

The file is UTF-8, and the boundary is *strict*: `ProgramTransferFormat.decode` uses a decoder that reports
malformed input and unmappable characters instead of substituting `U+FFFD`. The default
`String(bytes, UTF_8)` would turn a corrupt file into a program whose name quietly contains `?`, which is a
silent repair of input §14 calls untrusted. The writer escapes only what JSON requires (`"`, `\`, the five
short escapes, and `\u00XX` for other control characters) and writes every other character raw, so the bytes
are a function of the value alone.

## 17. Deterministic export

Byte-equivalence for equal input is a property of the writer's **shape** rather than of its care:

| source of variation | how it is closed |
| --- | --- |
| field order | the field lists are literals in the schema's own order |
| array order | a day list is the plan's order; weekdays and focuses are sorted into the domain's canonical order; a per-set target list is the plan's order |
| tokens | enum **names**, never ordinals, so a reordered enum cannot change a file |
| formatting | two-space indentation, `LF`, one trailing newline; scalar arrays inline, record arrays per line |
| numbers | decimal, no separators, no sign on a positive value |
| clock / randomness | there is no clock, no random source and no collaborator in the writer at all |
| unordered collections | a `Set<DayOfWeek>` and a `Set<Focus>` are written in ISO/canonical order, never iteration order |

## 18. The Android Share Sheet, and the `content://` contract

| element | value | why |
| --- | --- | --- |
| action | `Intent.ACTION_SEND` | §11's primary mechanism |
| payload | `EXTRA_STREAM` = a `content://` URI from this app's `FileProvider` | a `file://` URI is a `FileUriExposedException` on every device this app runs on (`minSdk` 24), so `content://` is mandatory rather than preferred |
| grant | `FLAG_GRANT_READ_URI_PERMISSION` on the share and on the chooser | the receiving app needs no permission of its own |
| MIME type | `application/json` | the file *is* JSON and its name ends `.json`; a `vnd.`-style type would hide it from apps that dispatch on what they can open |
| filename | `MonkFitnessProgram.mfp.json`, a constant | §5/§11: a fixed name for the **format** — no Program id and no runtime fact travels in a filename that other apps store and display |
| staging | `cacheDir/shared-programs/`, declared in `res/xml/program_file_paths.xml` | the grant covers one directory of regenerable files, never the app's data |
| provider | `androidx.core.content.FileProvider`, authority `${applicationId}.fileprovider`, `exported="false"`, `grantUriPermissions="true"` | derived from the application id so the manifest and `ProgramShareSheet.authority` cannot drift |

Nothing about the share is decided in the domain: the platform package names only `ProgramTransferFile` and
`ProgramTransferFormat`, and **no source under `domain/` names an `Intent`, a `Uri` or a `ContentResolver`** —
which the architecture suite asserts. Import acquisition is the platform's own document picker
(`ACTION_OPEN_DOCUMENT`, `CATEGORY_OPENABLE`, the format's MIME type), read through the `ContentResolver`,
bounded at the format's byte limit plus one byte so an oversized file is *reported* rather than loaded.

A manifest `VIEW` intent-filter for the format is deliberately **not** added: receiving a program file by
opening it from another app would make the import a launch destination, and §20 keeps this stage out of
navigation (§30 step 14 owns it).

## 19. The error mapping (§28)

| §28 class | the stage's vocabulary | recoverability |
| --- | --- | --- |
| `EXPECTED` | `UnsupportedFormatVersion`, `UnknownExercises`, `SchedulingRefused` | `USER_ACTION` (pick another file / set a start date), `TERMINAL` for a newer version |
| `INVALID_DATA` | `NotAProgramFile`, `SchemaInvalid`, `SemanticallyInvalid`, `ProgramNotFound` | `USER_ACTION` |
| `CONFLICT` | *not used*: an import creates a new Program and can conflict with nothing |
| `SYSTEM_FAILURE` | `ProgramTransferResult.Failed(cause)` | `RETRY` |

A persistence failure is **never** translated into a refusal, and no path returns an empty program, a `null`
or a `false` (§13, §33). The six distinctions §13 asks for are six typed cases, not six sentences: malformed
JSON, unsupported version, schema failure, unknown exercise, semantic failure and persistence failure.

## 20. No history, progress or adaptive contamination

Three independent guarantees, because one would not be enough:

* **the model**: `ProgramTransferDocument` and its leaves have no field a session, a set, a statistic, a
  streak, a family state, a decision or an adjustment could occupy (asserted by a field census over the whole
  hierarchy);
* **the collaborators**: `ProgramExportService` takes one argument — the repository that returns a Program
  with its current revision — and `ProgramImportService` takes the creation primitive, the Scheduler, the
  selection owner, the exerciseId port, the two §26 ports, the calendar and the transaction runner. Neither
  can reach a session, a progress fact or an adaptive row, because neither holds one;
* **the rows**: the round-trip suite seeds the source Program with a live session, confirmed sets, a family
  progression state, an adaptive decision and an adjustment, then asserts after the import that the source's
  rows are *exactly* what they were and that the imported Program owns **none** — counted in SQL, not in
  memory.

## 21. Compatibility and versioning policy

* a reader accepts exactly the versions it knows (`VERSION = 1`) and refuses the rest before the schema step;
* **adding an optional field is a version bump**, not an inline extension: a document that carries a field
  the reader does not define is refused, so a v1 reader can never half-understand a v2 file;
* the format's identity is the `format` marker plus the version, so "this is not a program file" and "this is
  a program file from a newer app" are different sentences;
* the transfer model carries no Room column names and no entity types, so a future **schema** change
  (a new column, a new table) cannot change a file's meaning, and a future **model** change is visible in the
  format only when the format is deliberately extended.

## 22. Dependencies: none added

The repository declares no serialization dependency, and this stage adds none. No JSON library was
introduced, and the reason is not minimalism for its own sake:

* `org.json` is an **Android platform** class: it is a stub in a JVM unit test and it would put the platform
  inside a layer §25 keeps free of it (the purity scan would fail on the import alone);
* a code-generating library would put a third-party dependency in the domain to serialize six leaf types;
* the format is small, closed and fully specified in §3 of this document, and a reader that must **refuse**
  something (a duplicate field, a trailing comma, a fraction, a lone surrogate, a nesting bomb) is a reader
  whose refusals are the specification — which is easier to keep honest in 300 lines than in a configuration.

The cost is real and stated: the codec is this repository's own code and has to be maintained (the
`ProgramTransferJsonTest` suite covers its syntax surface and the RED mutations cover the rules built on it).

---

## 23. Verification

Measured on this branch with `:app:cleanTest :app:testDebugUnitTest --rerun-tasks`, parsing the JUnit XML,
with the newest `timestamp` checked against `date -u`:

```text
pristine origin/main (17972ce)   257 classes / 2397 tests / 0 failures / 0 errors / 0 skipped
this branch, §30 step 13         265 classes / 2511 tests / 0 failures / 0 errors / 0 skipped   (+8 / +114)
```

Both are measured in this tree with `:app:cleanTest :app:testDebugUnitTest --rerun-tasks`, with the newest
JUnit XML `timestamp` checked against `date -u` (baseline `2026-09-19T19:28:53Z` vs `19:29:04Z`; final
`2026-09-19T20:35:07Z` vs `20:37:36Z`), and cross-checked against the sources: `grep -rho '@Test'` = 2511 and
`grep -rl '@Test'` = 265, which is exactly the parsed census. The other gates —
`:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin`, `:app:compileReleaseKotlin`,
`:app:compileReleaseJavaWithJavac`, `:app:assembleDebug` — are all `BUILD SUCCESSFUL`.
`:app:lintVitalRelease` fails pre-existing (`res/values/themes.xml` ResourceCycle plus
`ExpiredTargetSdkVersion`) and is reported separately, as in steps 7–12.

The focused §30-step-13 suites:

| suite | tests | what it pins |
| --- | --- | --- |
| `ProgramTransferJsonTest` | 14 | the strict reader (malformed shapes, escapes, depth, duplicate fields), the deterministic writer, the format's constants, strict UTF-8 |
| `ProgramTransferSchemaTest` | 14 | the marker, the version, the field/kind/token allowlists, per-variant field sets, the size limit, multi-finding reporting |
| `ProgramTransferValidationTest` | 14 | every §6 rule the domain's constructors would throw on, and the two the transfer layer deliberately does not restate |
| `ProgramExportServiceTest` | 10 | the allowlist at every level, lossless prescriptions/focus/schedule, no identity or lifecycle fact in the text, determinism, an export writes nothing, the missing-Program refusal |
| `ProgramImportServiceTest` | 34 | the pipeline's six refusal kinds, fresh identities, `IMPORTED`, the planned start date, no history/adaptive rows, source independence, both selection paths, atomicity at two legs, the Standard-Program case |
| `ProgramRoundTripTest` | 6 | §22: byte-equal definitions across three configuration shapes, every identity different, A's rows untouched and B's empty, the selection rule, idempotence |
| `ProgramTransferArchitectureTest` | 14 | the layers, the collaborators, the field census, one exerciseId implementation, the container wiring, no UI reach |
| `ProgramTransferPlatformBoundaryTest` | 8 | `ACTION_SEND`/priority tokens, no `file://`, the provider declaration and its path resource, the picker, the bounded read, the pure payload |

Two earlier stages' guards were **revised, not relaxed**, and each revision is recorded where it lives:

1. `CompositionRootArchitectureTest.theCompositionRootDecidesNothing` — the Scheduler's occurrence count in
   the container went from four to five, because §30 step 13's import service is the one consumer the
   Scheduler is handed to. The fifth occurrence is pinned exactly (`scheduler = programScheduler,` once), so
   the rule now says what it always meant: the container may hold the node and hand it over, never decide
   with it;
2. `ProgramAdaptiveIntegrationArchitectureTest.theCompositionRootHandsTheProducerAndTheConsumerOneCalendar`
   — the count of `zone = zone` went from two to three: the import is a third layer that turns an instant
   into a date, and it is handed the composition root's one calendar value. The revision *strengthens* the
   rule (every such layer shares the value) rather than loosening it.

## 24. RED evidence

```text
30 of 30 mutations caught · 0 missed · control: the un-mutated tree stays GREEN
every mutated production source restored byte-identically (md5sum -c)
```

One mutation per rule the stage states, including the two that a source-level suite would otherwise cover by
assertion alone: the writer's field order (§11's determinism) and the share's URI scheme. The rows that
matter most are the two that pin the *ownership* boundaries — the importer skipping the initial
opportunities, and the Scheduler inventing them from the revision's days instead of deciding them.

`scripts/program-import-export-red-mutations.sh` applies one mutation at a time to the production sources,
runs the focused §30-step-13 suites, restores the file from a backup, and proves the restoration by
`md5sum -c`. It pre-flights the tree (every anchor present exactly once, no replacement already present),
holds an exclusive lock, aborts if a mutation cannot be applied, and runs a control row whose un-mutated tree
must stay GREEN.

The focused oracle is this stage's eight suites **plus** `ProgramRepositoryTest`, and that addition is itself
a result: the row that mutates `ProgramRepository.createProgram` (removing the transaction that makes the
creation unit atomic for every caller) was reported as *missed* on the first run, because the run's oracle
did not include §30 step 3's suite — which catches it. Two things were learned, and both are recorded:

* the mutation **is** caught, by the suite that owns the guarantee (`aFailureWhileWritingThePlanLeavesNoProgramBehind`);
* an import's atomicity does not depend on that inner `inTransaction` alone: the importer composes the
  creation inside the app's own transaction runner, so the unit is carried by the composition as well. That
  is why this stage's own atomicity tests did not notice — correctly, because what they measure still held.

## 25. Claim → test

| claim | suite |
| --- | --- |
| the file carries the definition and nothing else | `ProgramExportServiceTest.theExportedTextContainsNoIdentityTimestampLifecycleOrRuntimeFact`, `…theDocumentIsExactlyTheAllowlistsFieldsAtEveryLevel` |
| prescriptions, focus, schedule and pinning survive losslessly | `ProgramExportServiceTest.prescriptionsRemainLossless`, `…authorshipAndPinningAreCarriedBecauseTheyAreContent`, `…theCurrentRevisionIsTheOneCarriedAndItsConfigurationIsCarriedAsStated` |
| the same Program produces the same bytes | `ProgramExportServiceTest.theExportedFileIsByteIdenticalForTheSameProgramEveryTime`, `ProgramTransferJsonTest.theWriterIsExactlyTheFormatAndAlwaysTheSameBytes` |
| an export writes nothing | `ProgramExportServiceTest.exportingWritesNothingAtAll` |
| malformed JSON is not an empty Program | `ProgramTransferJsonTest.malformedTextIsASyntaxFailureAndNeverAValue`, `ProgramImportServiceTest.malformedJsonIsRejectedAsNotAProgramFile` |
| a missing/unsupported version is its own answer | `ProgramTransferSchemaTest.aMissingFormatVersionIsARequiredFieldFinding`, `…anUnsupportedVersionIsItsOwnAnswerAndNotABagOfSchemaFindings`, `ProgramImportServiceTest.aMissingFormatVersionIsARequiredFieldFailure`, `…anUnsupportedFormatVersionIsItsOwnAnswer` |
| an unknown exercise refuses the file and names it | `ProgramImportServiceTest.anUnknownExerciseIsRejectedAndNothingIsImported`, `…severalUnknownExercisesAreReportedTogether` |
| semantic rules are decided before any write | `ProgramTransferValidationTest` (14), `ProgramImportServiceTest.anInvalidRestDayIsRejected` … `…anInvalidModeIsRejected` |
| the domain's own validation is reused, not restated | `ProgramImportServiceTest.aPlanWithoutADayIsRejectedByTheDomainsOwnValidation`, `…aBlankNameIsRejectedByTheDomainsOwnValidation`, `…aWorkDayThatPlansNothingIsRejectedByTheDomainsOwnValidation`, `ProgramTransferValidationTest.aWorkDayThatPlansNothingIsTheDomainIsRuleAndIsNotRestatedHere` |
| every imported identity is fresh | `ProgramImportServiceTest.everyIdentityIsFreshAndNoneComesFromTheDraftOrTheSource`, `ProgramRoundTripTest.everyIdentityDiffersWhileEveryDefinedFactIsEqual` |
| the imported Program is `IMPORTED`, not started, planned to start the day it arrived | `ProgramImportServiceTest.anImportedProgramIsPlannedToStartOnTheDayItArrivedAndIsNotStarted`, `ProgramRoundTripTest.anExportedStandardProgramBecomesAnIndependentImportThatIsNoLongerProtected` |
| the import creates the Scheduler's own opportunities | `ProgramImportServiceTest.savingCreatesTheProgramItsFirstRevisionAndItsInitialOpportunities` |
| no history, no adaptive state, from either side | `ProgramImportServiceTest.theImportedProgramOwnsNoHistoryAndNoAdaptiveState`, `ProgramRoundTripTest.theSourcesHistoryAndAdaptiveStateAreUntouchedAndTheImportsAreEmpty` |
| a failed import leaves nothing, and selects nothing | `ProgramImportServiceTest.aFailureWhereThePlanIsWrittenLeavesNoProgramNoRevisionAndNoOpportunity`, `…aFailureWhereTheOpportunitiesAreWrittenLeavesNoProgramAtAll`, `…aFailedImportSelectsNothingEvenWhenTheChoiceIsOn` |
| selection is off by default and moves only when asked | `ProgramImportServiceTest.theDefaultLeavesTheSelectionExactlyAsItWas`, `…theExplicitChoiceSelectsTheImportedProgram`, `ProgramRoundTripTest.theSelectionMovesOnlyWhenTheImportExplicitlyAsksForIt` |
| the Scheduler keeps ownership of the opportunities | `ProgramTransferArchitectureTest.theImporterNeverBuildsAnOpportunityAndTheSchedulerStillDoes` |
| the boundary never leaves the platform | `ProgramTransferPlatformBoundaryTest` (8), `ProgramTransferArchitectureTest.theOnlyPlaceAnIntentAUriOrAContentResolverAppearsIsThePlatformPackage` |
| the format has no identity to leak | `ProgramTransferArchitectureTest.theTransferModelHasNoFieldAnIdentityTimestampSourceOrLifecycleFactCouldOccupy` |

## 26. Findings, decisions and what remains

### A finding of this stage: the pre-P13 tree never checked an id against the library

`ProgramGraphFixture` — the graph fixture §30 steps 3–12 were built on — plans exercises with the ids
`pushup` and `pike_pushup`. `WorkoutGenerator`'s catalogue holds **`pushups`** and **`pike_pushups`**. Nothing
before §30 step 13 ever asked a library whether a plan element's id exists, so the difference was invisible;
an import is the first boundary that *must* ask, and it correctly refused the file (twice, from two different
suites) the first time the transfer tests tried to round-trip a Program built from that fixture.

The response is the honest one and is deliberately narrow: the **transfer** fixtures plan with the
catalogue's own ids (`ProgramTransferRig.withCatalogueExerciseIds`), and the older fixture is left exactly as
it is, because the suites built on it are not this stage's to move. The finding is recorded here, and
`ProgramTransferArchitectureTest.theProductionLibraryKnowsTheExercisesTheTransferFixturesPlanWith` keeps it
visible. It is a fact about the shipped catalogue rather than a defect in the fixture — but it is also the
first evidence that the question had never been asked.

### Decisions taken, with the alternative they rejected

1. **No JSON dependency** (§22). Rejected: `org.json` (Android platform class, a stub on the JVM) and a
   code-generating library (a domain dependency for six leaf types).
2. **`ProgramSource` is not serialized** (§6). Rejected: carrying it and overwriting it on import.
3. **The transfer layer decides the rules the domain would throw on, and reuses
   `ProgramDraftValidation` for the rest** (§9). Rejected: restating the domain's plan-wide rules in the
   transfer layer.
4. **`initialSlotsFor(program, revision)` on the Scheduler** (§13). Rejected: creating the Program first and
   scheduling it afterwards (§27 asks for one unit), and letting the importer build slots (a second copy of
   the timing rules).
5. **The imported Program is planned to start on the day it arrived** (§8). The alternative — leaving
   `plannedStartDate` null — makes §27's *"initial Slots"* unsatisfiable, because the Scheduler refuses to
   plan without an anchor and inventing one there would be the Scheduler deciding when a user's program
   begins. **This is the one product-visible decision of the stage and it is open to review**: an alternative
   is an explicit date choice in the import UI (a §30 step 14 question, not this stage's), and changing the
   date afterwards is `ProgramLifecycleService.setPlannedStartDate` plus a scheduling pass — both of which
   exist.
6. **A day position is not a field** (§3). Rejected: carrying positions so a document could state a day
   number independently of the array.
7. **`application/json` rather than a vendor MIME type** (§18). Rejected: a `vnd.` type that would hide a
   shared program from every receiving app that dispatches on what it can open.

### What this stage deliberately did not do

* **no UI**: no screen, no route, no ViewModel change. §20 keeps this out of navigation; the mechanism is
  complete and callable, and the affordance belongs to §30 step 14. The architecture suite asserts that no
  UI or ViewModel source names this stage yet;
* **no schema migration**: the deployable database version is unchanged (Q is a transfer boundary, not a
  persistence-model rewrite). The imported Program is an ordinary Program in the existing schema;
* **no ACTION_VIEW intent filter**: see §18;
* **no legacy removal** (§30 step 15), and no change to the Stage-1 adaptive generation;
* **no change to P11/P12's adaptive behaviour**: the only production file outside this stage's own scope is
  `ProgramScheduler.kt`, and the change there is additive (a third entry point plus one shared private
  decision helper).

### Open items

1. **The import's planned start date** (decision 5) — recorded as the stage's one product-visible choice;
2. **Preview-before-save for a large file** is not implemented: the review step is complete and cheap, but
   nothing in this stage chooses when a screen shows it;
3. **A persisted family ladder and an exercise→family classification** remain the adaptive generation's
   blockers (§30 step 12's document), and nothing here changes that.
