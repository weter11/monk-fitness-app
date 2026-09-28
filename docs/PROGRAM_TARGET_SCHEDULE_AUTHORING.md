# Stage 19 — the explicit target schedule authoring path

## What this stage is

Stage 18 gave the immutable revision a place to *hold* an explicit target source, and said plainly
that nothing could yet produce one:

> **Authoring target source.** No UI, editor or generator path can yet state one.

This stage adds that path, and nothing else. It is the missing half of Stage 18:

```text
caller-owned target scheduling authoring values
        ↓  ProgramSaveService.save(draft, plannedStartDate, targetSchedule)
creation / revision-save application boundary
        ↓  TargetScheduleSourceRepository.store(...)
revision-owned explicit target source
```

**It does not perform the production target cutover.** `ProgramScheduler` remains production
scheduling's owner, `TargetScheduleOrchestrator` is still unconnected, and `TargetScheduleSourceBridge`
is still consumed by nobody. Nothing in this stage schedules anything.

---

## Ownership: the caller

`TargetScheduleAuthoring` is **caller-owned configuration**. The caller states the values; the
application boundary attaches them; the repository persists them. No component in between decides one.

The value lives at `domain/usecase/TargetScheduleAuthoring.kt`, beside Stage 18's `TargetScheduleSource`
and the `TargetScheduleDefinition` it reuses.

## Exact contents

```kotlin
class TargetScheduleAuthoring(
    rules: List<TargetScheduleDefinition>,
    programDayBindings: List<TargetScheduleAuthoringBinding>
)

data class TargetScheduleAuthoringBinding(
    val workoutId: String,
    val draftedProgramDayId: ProgramDayId
)
```

`TargetScheduleDefinition` is the caller-owned rule Stage 13 already defined and this stage reuses
verbatim rather than duplicating:

| field | stated by the caller as |
| --- | --- |
| `ruleId` | the rule's own identity |
| `workoutId` | the workout the rule produces |
| `cadence` | one of `Daily`, `EveryNDays`, `SessionsPerWeek`, `FixedWeekdays`, `DerivedExcluding` |
| `anchorDate` | the date the rule is anchored to |

Both lists are copied at construction, so a caller mutating its own list cannot rewrite the value.
Equality is field-by-field, so "the authoring round-tripped unchanged" is a comparison rather than a
hope.

The value holds no repository, DAO, Room entity, clock, identity generator, scheduler or UI state, and
`asSourceOf` is the only thing it can do.

## What is deliberately absent

The authoring states **no** other fact, and nothing in the pipeline supplies one:

* no `ruleId`, `workoutId`, `anchorDate`, cadence or `DerivedExcluding` source is defaulted, chosen,
  normalized or completed;
* no binding is manufactured, and no binding is re-pointed at a day the caller did not name;
* no target schedule is derived from weekdays, from `ProgramDay.position`, from `ProgramDay.name`, from
  a `ProgramDayId`'s own text, from a date, or from a revision number;
* **the Program's `plannedStartDate` is never used as a rule's `anchorDate`.** No documented ownership
  rule in this repository equates the two, so the anchor remains an explicit authoring input. The
  architecture suite asserts both halves of this: that no `anchorDate = plannedStartDate` assignment
  exists, and that the anchor reaches the source only through the caller's own value.
* an authoring that states **no rule** is refused (`NoRulesStated`) rather than stored.

## Why it is not part of `ProgramEditorDraft`

A draft is the Program's *structure* ([ProgramStructure]), and the structural comparison is what decides
whether a save warrants a revision at all (§6). Target semantics are not structure. Putting them on the
draft would force one of two bad shapes:

* a target-only change would mint a revision, because the draft would no longer compare equal to the
  current plan — a scheduling decision made by a comparison that knows nothing about scheduling; or
* the field would be excluded from the comparison, and a target change would then be silently invisible
  to a save that reported success.

Neither is honest, and both are decisions this value must not make. `TargetScheduleAuthoringArchitectureTest
.theAuthoringIsNotCarriedOnTheDraftAndTheLegacyScheduleIsNotExtended` pins the absence on the compiled
field lists of both `ProgramEditorDraft` and `ProgramSchedule`, so the exclusion is a structural fact
rather than a promise.

## Why it is not part of `ProgramSchedule`

A revision's legacy `ProgramSchedule` states *when its slots fall*. Two forms exist
(`FixedWeekdays(weekdays)`, `FlexiblePerWeek(sessionsPerWeek)`) and between them they answer one
question: *is this date a training date?*

A target rule states something strictly wider — a rule identity, a workout identity, a cadence and an
anchor date, plus, for a derived rule, the identity of the rule it derives from. **Three of a rule's four
facts have no counterpart in the legacy vocabulary at all**, so any mapper between the two would have to
manufacture them, and Stage 18 already established that no such mapper exists. Extending the legacy
schedule instead of adding a separate value would put a second, derived vocabulary into the type whose
whole job is to be the legacy one.

## The binding and its re-identification

`TargetProgramDayBinding` — which the occurrence presenter refuses to infer — is stated explicitly, one
per workout the revision presents.

A binding names the plan day by the **draft's** `ProgramDayId` handle, not by the saved identity. That is
forced by §6: a save mints a fresh identity for every plan day, so the final `ProgramDayId` does not
exist until the save has run, and a caller authoring against a draft cannot know it. Naming the drafted
handle is what makes the binding authorable at all.

`asSourceOf` then re-points each stated handle through the correspondence the editor reports:

```kotlin
val minted = mintedProgramDays[binding.draftedProgramDayId]
    ?: throw TargetScheduleAuthoringException.ProgramDayNotInTheSavedRevision(...)
```

This is a **re-identification of a fact the caller stated**, never a choice of which plan day a workout
presents. A handle the saved revision does not carry is refused, not guessed at.

## Why the editor owns the correspondence

`ProgramEditorService.mintRevision` is the only place in the codebase that re-identifies a plan (§6: a
revision never reuses a row of the revision it replaces). It therefore reports what it did, rather than
leaving a second component to re-derive it:

* `ProgramCreation.mintedProgramDays` — for a create or a copy;
* `ProgramSaveOutcome.RevisionSaved.mintedProgramDays` — for an edit or a structure-level create.

The two travel together so the correspondence can never be reported for a different revision than the
one it minted. The editor names **no** target-stage type at all — asserted mechanically by
`theEditorStillReachesNoTargetStageType` — so the authoring seam sits above the editor, not inside it,
and the editor's ownership of *structure* is unchanged.

## Create and Copy

Both entry points run `ProgramSaveService.createProgram`, because a copy's draft names no Program
(`programId == null`, §7). The order is unchanged and is still the contract: the editor's answer
(validation, minted identities), then the Scheduler's answer (initial opportunities, computed against
values, touching no row), then **one** transaction:

```text
inTransaction {
    programRepository.createProgram(program, revision, slots)
    stateTargetSourceFor(revisionId, mintedProgramDays, targetSchedule)
}
```

The source is written against **the revision this save created** and for no other. A copy is a new
Program with its own first revision and its own minted day identities, so it gets its own source, and the
revision it was copied from is not touched.

## Edit

A structural edit runs the existing §27 pair inside one transaction. The stated source rides the same
unit and obeys the same rule:

```text
inTransaction {
    editor.save(draft)                                   → at most one new Revision (§6)
    stateTargetSourceFor(saved.revision.revisionId, saved.mintedProgramDays, targetSchedule)
    scheduler.schedule(programId)                        → the one reconciliation pass
}
```

* the source is written against the **new** revision, never against the one the save replaced, so the
  previous revision's stored source survives byte-for-byte;
* a save that creates **no** revision (a no-op save, or a facts-only save) states no source at all —
  there is no new revision to own one, and attaching it to the existing revision would be a mutation of
  a saved revision;
* a refused or failing source write rolls the revision back with it, which is the same all-or-nothing
  the reconciliation already obeys.

## Omission

`targetSchedule == null` is the whole of the absent case: `stateTargetSourceFor` returns immediately,
no row is written, no `TargetScheduleSource` is constructed, and the revision reads back as
`TargetScheduleSourceRead.Missing`.

This is deliberately **not** an empty source. A revision with no rules cannot produce an occurrence at
all, so "this revision states no target semantics" and "this revision states that it has none" are
different claims and only the first is representable. `Missing` is the truthful reading of every revision
whose author never stated target semantics and of every revision saved before this existed.

Note that the *behavioural* difference of a wrongly-constructed empty source is invisible at the row
level — an empty source writes no rows, so the read would still say `Missing`. The rule is therefore
pinned where it is actually decided, in the shape of the absence branch:
`TargetScheduleAuthoringArchitectureTest.theAbsentCaseIsAReturnAndNeverAnEmptySource`.

## `NoRulesStated`

An authoring with no rules is refused rather than stored. A revision with no rules cannot produce an
occurrence, so such an authoring is not a source claiming "no target semantics" — it is a statement that
cannot be stored, and the typed absence is reached by *not supplying an authoring*. Measured by
`anInvalidSourceClaimStillFailsThroughStageEighteensTypedRefusals`, and proved load-bearing by the RED
row that narrows the check.

## Revision immutability

Unchanged from Stage 18, and not weakened here:

* Stage 18's DAO still declares **no `UPDATE` and no `DELETE`** — two inserts and two revision-keyed
  reads — so immutability is structural, not conventional;
* a saved revision's source is written once: an identical repeat writes nothing, and a different source
  is refused with `ConflictingStoredSource`;
* this stage adds no update path, no delete path, and no way to attach a source to a revision the save
  did not create.

The RED suite's last row mutates Stage 18's immutability check directly; its catch is owed by
`TargetScheduleSourceRepositoryTest`, the suite that *owns* that primitive, which is why that suite is in
the script's oracle list.

## Transaction and atomicity boundary

The target source belongs to the same revision-creation unit as the revision itself, so it is written
inside the transaction that already owned that unit — the creation unit for a create or a copy, and the
revision-plus-reconciliation unit for an edit.

The rollback is measured on the source's **second** write
(`FailingProgramTargetScheduleSourceDao.insertBindings`, `ProgramDaoFaults.failTargetBindingInsert`),
because a fault on the first statement would prove only that nothing ran. When the fault fires, the
Program, the revision, the plan, the initial slots *and* the source's own rule rows are already on disk,
and all of them have to disappear — asserted for both legs in
`aFailedSourceWriteRollsTheCreationUnitBackAndLeavesNoOrphanedSource` and
`aFailedSourceWriteRollsAStructuralEditBackWithItsRevision`, each paired with a
"clear the fault and it lands" case so the assertion is a rollback and not a path that never writes.

A **refused** source is likewise measured: `aRefusedSourceRollsTheWholeCreationUnitBack` plants a
duplicate rule identity, which Stage 18 refuses *before* anything reaches the database, and asserts both
that the failure is Stage 18's own typed refusal and that not one row of any table moved.

## Why legacy-only callers remain valid

`targetSchedule` is a defaulted parameter, so every existing call site — `ProgramsController`'s
`saver.save(draft, plannedStartDate)`, the transfer rigs, every existing suite — is unchanged and states
no target semantics. A Standard Program, an imported Program, a legacy revision and any legacy-created
Program all continue to read as `TargetScheduleSourceRead.Missing`, and none is backfilled or inferred.

## No target fact is inferred

Stated once here because it is the stage's whole discipline, and asserted from three directions:

* **behaviourally** — `noTargetFactIsManufacturedFromTheDraftsLegacySchedule` and
  `aCreateWithNoAuthoringStoresNoSourceEvenThoughItsLegacyScheduleIsRich` drive a draft with a rich
  `FixedWeekdays` schedule and read the stored source back: it is exactly the stated rules, and it is
  `Missing` when nothing was stated;
* **architecturally, over the whole production tree** —
  `noProductionSourceMapsALegacyScheduleOntoATargetSchedule` fails a file that names a legacy schedule
  *and* constructs a target rule;
* **architecturally, on the authoring path** — the authoring value and the save boundary name no legacy
  schedule vocabulary at all (whole-token, with `_` a word character, so the `ProgramScheduler`
  collaborator §20 requires is not refused for containing the substring `ProgramSchedule`).

## No second policy in the editor or the save layer

The save layer applies a stated source and decides nothing about target scheduling. It holds no cadence
branch, no window, no composition selection, no pause, no `asOf`, no planner, no resolver, no composer,
no presenter, no policy and no orchestrator; it does not consume the bridge; and it calls Stage 18's
`store` exactly once, from exactly one place. Asserted by
`theEditorAndSaveLayerHoldNoTargetSchedulingPolicyOfTheirOwn`,
`theEditorStillReachesNoTargetStageType` and
`theAuthoringIsAppliedOnlyThroughTheStagesEighteenRepository`.

## Import/export remains deferred

**The transfer format was deliberately not extended in this stage**, and the decision is preserved from
Stage 18 for a reason this stage sharpened rather than removed.

Stage 18 deferred it because no authoring path existed. One now does — and that is *not* by itself a
reason to extend the transfer contract. The rule Stage 18 set, and this stage keeps, is that the
extension belongs here **only if a complete, explicit authoring representation can be transferred without
inference**, and adding a documented field rather than interpreting `ProgramSchedule`.

This stage established the authoring *value*, not a transfer *representation* of it. A transfer field
would still have to be either omitted — producing a Program whose revisions read as `Missing`, which is
honest but transfers nothing — or populated by inference, which is the mapping this contour exists to
prevent. The two legitimate ways forward are a decision, not an implementation detail: either the caller
of the export supplies a source explicitly (an export *request* carrying target semantics, which is a
different contract from §5's "Program definition/configuration only"), or the format grows a documented
target field with its own round-trip. Neither is established here, so the transfer model is untouched and
`theImportExportContractIsUntouchedByThisStage` asserts it over the whole transfer package.

## Production scheduling is unchanged

* **`ProgramScheduler` remains production scheduling's owner.** It is untouched, and
  `theLegacySchedulerIsStillProductionsSchedulingOwner` asserts that `ProgramScheduler.kt`,
  `SlotPlanner.kt` and `ScheduleCalendar.kt` name no target type, no target table and no target source.
* **`TargetScheduleOrchestrator` remains unconnected to production scheduling.**
  `productionConsumersStillDoNotRunATargetPass` asserts that the save boundary runs no pass, consumes no
  bridge, and that no file outside the composition root and the bridge itself constructs the bridge.
  Stage 18's own gate makes the same statement and is untouched and green.

## Architecture gates

`TargetScheduleAuthoringArchitectureTest` states thirteen mechanical claims against the sources, with
comments stripped first, so a KDoc may *say* "never reads `ProgramSchedule`" without tripping the rule
about reading it:

1. the authoring path adds exactly the files it claims;
2. the authoring value names no legacy schedule vocabulary;
3. the save boundary names no legacy schedule vocabulary either;
4. no production source maps a legacy schedule onto a target schedule;
5. the authoring is on neither the draft nor the legacy schedule;
6. the authoring reads no Scheduler state;
7. the authoring reads no resolved occurrence, slot, session or performance state;
8. the authoring holds no collaborator and no ambient state;
9. the source is caller-owned configuration and nothing else;
10. the planned start date is never used as an anchor date;
11. the absent case is a return and never an empty source;
12. the editor and the save layer hold no second target-scheduling policy;
13. the source is applied only through Stage 18's repository, in one place;
14. production consumers still run no target pass;
15. the legacy Scheduler is still production scheduling's owner;
16. the import/export contract is untouched.

## What remains deferred

* **The production cutover.** Unchanged: `ProgramScheduler` schedules production, and
  `TargetScheduleOrchestrator` is not connected to it.
* **A UI authoring surface.** This stage adds the *seam*; the editor screen still supplies no authoring,
  because wiring one would be the cutover's neighbour and is not this stage's scope.
* **Reading a stored source into a running pass.** `TargetScheduleSourceBridge` still returns the facts
  to nobody; the future caller that composes the other eight `TargetScheduleInput` values does not
  exist.
* **Transfer.** As above: the authoring value exists, a transferable representation of it does not.
