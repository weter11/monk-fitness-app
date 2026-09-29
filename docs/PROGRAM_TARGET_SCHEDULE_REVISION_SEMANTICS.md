# PROGRAM_TARGET_SCHEDULE_REVISION_SEMANTICS

**§30 step 22 — target schedule is revisioned Program behaviour.**

Branch: `feat/program-stage22-target-revision-semantics`
Base: `d3a96b5` (merge of PR #323, §30 step 21)

**Measured result** (full run, `bash scripts/program-stage22-red-mutations.sh`):

```text
control GREEN
caught: 19
missed: 0
not-a-catch: 0
every mutated source restored byte-identically
```

**Census** (`./gradlew --offline :app:cleanTest :app:testDebugUnitTest --rerun-tasks`, gated on exit 0):

```text
test classes   290
@Test count    2765   (cross-checked: `grep -rho '@Test' app/src/test/java | wc -l`)
failures         0
errors           0
skips            0
focused Stage 22  35   (17 architecture + 18 integration)
```

---

## 1. The rule this stage establishes

```text
Target schedule is revisioned Program behaviour.

Target change:
    Replace / Clear
        → new Revision

No target change:
    Keep

Structural Program edit:
    target source is carried forward unless explicitly Replace/Clear

Revision source:
    immutable
    revision-owned
```

Expanded into the cases the application boundary now decides:

```text
no structural change + no target change   → no Revision
structural change + Keep                  → new Revision, source carried forward
structural change + Replace               → new Revision, new source on it
structural change + Clear                 → new Revision that states no target source
target-only Replace                       → new Revision, same Program, same structure
target-only Clear                         → new Revision that states no target source
target-only Keep                          → nothing
previous Revision                         → immutable, its source untouched
```

It is not a new lifecycle state and not a new Program. A target-only change keeps the same `ProgramId`,
the same name, the same lifecycle status and the same structure; only `currentRevisionId` moves.

## 2. The defect this stage fixes

Stage 19 made a target source authorable and applied it to the revision a save creates. It could not
express *"do not change it"*, because the absent case was a single `null` standing for three different
claims:

| what the caller meant | what `targetSchedule = null` did |
| --- | --- |
| *keep what the current revision states* | wrote nothing — **erased the source** |
| *state that this revision has none* | wrote nothing — indistinguishable from the above |
| *there has never been any target semantics* | wrote nothing — correct, for a creation only |

The middle row is the defect. A Program that stated a target schedule and whose owner then edited a
plan day — the ordinary structural save — silently lost its target scheduling on save, with no refusal
and no notice. §6's structural comparison could not save it, because a target source is not structure:
the draft was byte-identical to the current revision and the editor correctly answered *nothing to
change*, so the stated schedule was discarded by a comparison that never claimed to look at it.

Stage 22 gives the claim a vocabulary and gives the change a path.

## 3. Why a dedicated application revision mechanism was needed

Stage 19's boundary was right to keep target semantics **out** of `ProgramEditorDraft`, and that
decision needed this stage rather than being revised by it.

The structural comparison *is* the mechanism that decides whether a save warrants a revision (§6).
Putting a target source on the draft would have made the comparison answer two questions at once, and
it would have had to answer them inconsistently:

* a **target-only** change has an identical structure, so a comparison that ignored target content
  would mint no revision — the change becomes invisible and is dropped;
* a comparison that *did* read target content would be the structural comparison deciding a scheduling
  question, and every fact it would need (which rules, which bindings, whether they differ) would have
  to be a draft field. The draft is a value the editor's operations replace and a save compares; a
  target source is a caller-owned statement that changes on a different trigger.

So the two changes are separated at the boundary rather than fused in the draft:

* `ProgramEditorDraft` still means Program **structure**, and `ProgramEditorService` still decides
  revisions **from structure alone**;
* `ProgramSaveService` now decides **whether a target change warrants a revision** — which §6 does not
  forbid, because §6 constrains *structural* comparison to structure and says a change to the saved
  plan is a new revision. A target source is part of the saved plan (§18's rule table owns it, and
  §6 makes the revision immutable), so changing it is a change to the plan.

Revision minting stayed in the editor (§6's single owner). The save boundary asks it for a revision
whose structure is the current one and decides whether to keep it.

## 4. The value: `TargetScheduleRevisionChange`

```kotlin
sealed interface TargetScheduleRevisionChange {
    data object Keep
    data class Replace(val authoring: TargetScheduleAuthoring)
    data object Clear
}
```

Named from `declaredClasses`, so adding a fourth case fails the gate until someone decides what an
unstated claim means. `Replace` carries exactly the authoring; `Keep` and `Clear` carry nothing at
all, which is what makes "there is no `null` here" a property of the shape rather than a convention.

### The two absences, and which leg each belongs to

`ProgramSaveService.save` takes both vocabularies, each on the leg it belongs to, and refuses either
one on the wrong leg:

| parameter | legal on | its `null` means |
| --- | --- | --- |
| `targetSchedule: TargetScheduleAuthoring?` | a **creation** (create / copy) | this Program has never stated target semantics — the revision reads `Missing` |
| `targetChange: TargetScheduleRevisionChange?` | an **edit** of an existing Program | `Keep` — carry the current revision's source forward |

```text
creation + targetChange = Keep   → EditChangeOnACreation
edit + targetSchedule != null   → CreationAuthoringOnAnEdit
```

So `null` means exactly one thing per parameter, and neither leg can be given the other's vocabulary
by accident. The refusals fire **before** the transaction opens, so nothing is written either way.

## 5. Read side: existing source → authoring

```kotlin
fun TargetScheduleSource.asAuthoringOver(
    draftedProgramDays: Set<ProgramDayId>
): TargetScheduleAuthoring
```

A stored binding's `programDayId` is a plan day of the revision that stated it, and an editor draft
opened from that revision carries the same identities as its own handles (`editDraft` copies the plan;
it does not re-identify it). So the stored identity *is* the drafted handle, and the correspondence the
conversion needs is a membership question: does this draft still carry that day?

Nothing is derived from a `ProgramDay`'s `position`, `name`, date, weekday or index. No `workoutId` is
derived from a `ProgramDayId`. A binding whose day the draft does not carry is **refused** with
`TargetScheduleAuthoringException.StoredProgramDayNotInTheDraft` rather than re-pointed at a
substitute — which plan day that workout presents now is the caller's statement, not a function of the
day that disappeared.

## 6. Structural edit with `Keep`

```text
Revision A   source A
edit structure, target change = Keep
Revision B   source B ≡ source A   (same rules, same workouts, same plan days — re-identified)
```

`Keep` reads the **current** revision's stored source *before* the editor's save moves
`currentRevisionId` (after the save it would name the revision being superseded), converts it to an
authoring over the draft's own plan days, and lets that authoring be re-pointed through the
`mintedProgramDays` correspondence the save reports. Old `ProgramDayId`s are never copied into the new
revision's rows — §6 re-identifies every plan day on every save, and the binding names the identity the
editor minted for that handle. A structural save still runs §20's reconciliation, as it always did.

## 7. Structural edit with `Replace`

The new source is written **against the new revision only**, inside the same transaction as the
revision, the plan days, the plan elements and the reconciliation. Source A is untouched. A
programmatic row for this in the RED suite writes the same source against
`draft.baseRevisionId` — the revision the draft was opened from — and the repository refuses it, which
is the point of the check it performs.

## 8. Target-only change

The defect's other half. The path is `ProgramSaveService.saveTargetScheduleChange`, and the order is
the contract:

1. **read what is stored** — the current revision's source, before anything is minted, because the write
   moves the pointer;
2. **mint, unwritten** — `editor.prepareTargetScheduleRevision(programId)` returns the new revision and
   the correspondence from each current plan-day identity to the identity it becomes. No row is written,
   so an identical statement costs nothing;
3. **compare semantics** — `TargetScheduleAuthoring.statesTheSameTargetSemanticsAs`, in the space of
   plan-day handles, *before* either statement is attached to an identity;
4. **write once, or not at all** — `editor.saveTargetScheduleRevision(prepared)` and the source store,
   inside **one** transaction.

`Keep` mints nothing (a change that changes nothing is not a change). `Clear` on a revision that
already states no source is the same claim storage already makes, and likewise mints nothing.

### Why the comparison is semantic and not by identity

§6 re-identifies **every** plan day on **every** revision, so a source that states exactly the same
rules and the same `workoutId -> plan day` bindings never compares equal by value once it has been
attached to a new revision. An identity comparison would mint a revision on every save of an unchanged
target schedule — precisely the opposite of the rule.

The comparison is field-by-field over the two things a source states:

* **rules** keyed by `ruleId`, **bindings** keyed by `workoutId`, both order-insensitively.

Both keys are total — the repository refuses a duplicate rule identity and a duplicate workout binding
— so no two entries collapse and no ordering hides a difference. Order-insensitivity is a decision
rather than an accident: an immutable-write guard that compares whole values inherits its definition
of *"identical"* from whatever `ORDER BY` the read uses, so a caller re-presenting the same rules in a
different order would be refused as a *conflicting* source. Here the domain value decides, and it
decides that rule order is not part of the claim.

## 9. `Clear`

```text
Revision A   source A
TargetScheduleRevisionChange.Clear
Revision B   B states no target source   (TargetScheduleSourceRead.Missing)
             A still has source A
```

Nothing is deleted and nothing is mutated. It does not mean *"mutate A"*, and it does not mean *"leave
A current and drop its source"* — A is superseded by a new revision that states nothing, and A keeps
what it stated.

`Clear` is **not** an empty `TargetScheduleAuthoring`. An authoring that states no rule is refused with
`NoRulesStated`: a revision with no rules cannot produce an occurrence at all, so it is not a storable
source. "This revision states no target semantics" and "this revision states that it has none" are
different claims, and only the second is representable. Stage 19's `NoRulesStated` remains valid and is
asserted to.

## 10. Copy semantics — the contract chosen

```text
Copy without explicit target authoring
    → the copied Program has no target source
```

A copy mints fresh plan days and shares nothing with its source (§6, §23), so there is **no**
correspondence from the source Program's stored bindings to the copy's own days for anything to be
inferred from. The copy path is the creation path, and it reads no stored source at all — which is
asserted over the creation leg specifically, since the boundary *does* read a source on the edit leg.

The prohibited inference:

```text
old ProgramDayId → guessed new ProgramDayId by position
```

never happens anywhere on this path. A caller that wants the copy to have a target source states an
authoring against the copy's own drafted days, exactly as for any other creation.

## 11. Transaction semantics

One transaction covers `Revision + ProgramDays + ProgramExercises + target source + the existing
reconciliation writes`. A source that is refused or that fails to write rolls the revision back with it.

The fault in the RED suite goes on the **binding** insert, which follows the rule insert, so at the
moment it throws a new revision and its rule rows genuinely exist on disk. A fault on the first
statement would prove only that the unit never started — a strictly weaker claim that passes even for
code that wraps nothing. The suite asserts the planted failure propagates, the whole table census is
back to what it was, the Program still points at the revision it had, that revision's source is still
readable, **and** that clearing the fault lets the same change land — so the assertion is a rollback and
not a code path that never writes.

Stage 21's known gap is unchanged and not widened here:

```text
Start lifecycle write
    →
target scheduling transaction
```

No abstraction spans those two boundaries, and this stage did not add one.

## 12. Legacy isolation

`ProgramScheduler`, `SlotPlanner` and `ScheduleCalendar` are untouched. A target-only change runs **no**
scheduling pass at all: a target revision change is not a legacy schedule change. A structural save still
uses §20's existing reconciliation, which belonged to the current Program save contract before this
stage and still does.

No mapping was added in either direction, and the gate asserts the legacy contour reaches no target
type, no target table and no target source.

## 13. Why UI authoring remains deferred

A UI authoring surface is the next stage's job, and it is deliberately not started here. The reason is
the ordering: authoring needs a persistence contract it can be honest *about*, and until this stage
there was none — a UI control for target scheduling would have had to choose between two contracts,
one of which erased the user's schedule. The contract is now fixed, so the next stage can present
`Keep / Replace / Clear` without deciding what a user's silence means.

`ProgramEditorScreen`, the `ProgramsController` flow, the Compose controls and the import/export UI are
unchanged. The production caller of `save` (`ProgramsController.save`) still passes no target statement
at all, and now gets `Keep` — which is the correct behaviour for a UI that has not yet offered
authoring, and the reason nothing regresses for an existing user.

## 14. Pins revised rather than relaxed

Three claims from earlier stages changed, and each was **restated to the claim that is still true**,
with the reason recorded in the test:

| test | before | after | why |
| --- | --- | --- | --- |
| `TargetScheduleAuthoringArchitectureTest.theEditorReachesNoTargetSchedulingPolicyAndTheOneTargetNameItHoldsIsItsOwnReturnType` (was `theEditorStillReachesNoTargetStageType`) | the editor names **no** target-stage type at all | the editor holds no target *scheduling policy* — no source, no authoring, no repository, no cadence vocabulary, no pass — and the one target name it may hold is its own return type, checked on its compiled shape | the old absence was an accident of what existed; a target-only change must mint a revision, and §6 gives the editor sole ownership of minting, so the editor necessarily names the value it hands back. The prohibition is now stated positively and covers strictly more tokens. |
| `TargetScheduleAuthoringArchitectureTest.theAuthoringIsAppliedOnlyThroughTheStagesEighteenRepository` | `store(` appears in exactly **one** place | `store(` appears in exactly **three** places — the three legs this contract has — and the boundary writes no target row by any other route | the count was standing in for "a second application path cannot appear unnoticed". A fourth leg would still be refused, and the claim that actually protects the contour (one repository, no DAO) is now asserted directly. |
| `TargetScheduleAuthoringTest.aStructuralEditStoresTheNewlySuppliedSourceOnTheNewRevision` and `aNoOpEditThatStatesAnAuthoringStoresNoSourceBecauseItCreatesNoRevision` | an edit passed `targetSchedule = authoring` | an edit passes `targetChange = Replace(authoring)` | the same claim, through the parameter the new contract owns it through. Passing a bare authoring on an edit is now `CreationAuthoringOnAnEdit`, which is the point. |

Stage 18's and Stage 21's gates are untouched and green.

## 15. Architecture gate

`TargetScheduleRevisionArchitectureTest` — eighteen mechanical claims, comments stripped first so a
KDoc may *say* "never infers a binding from a position" without tripping the rule about doing it:

1. target authoring remains outside `ProgramEditorDraft` (and the change vocabulary is not on it either);
2. the change is exactly `Keep / Replace / Clear`, read from `declaredClasses`, and `Replace`/`Clear`
   carry what they carry;
3. a target-only change has its own operation, minted through the editor's explicit entry, and its
   "already stated" test is a semantic one;
4. revision minting has exactly one owner — the save boundary and the change value hold no
   `IdGenerator`, the editor declares one `mintRevision` and every entry delegates to it, and exactly
   one production source writes a new revision;
5. an old target source is never updated or deleted — the repository exposes no such operation and the
   boundary calls no such route;
6. a new source attaches only to the new revision, and `Keep`'s read of the previous one is explicit;
7. bindings are re-identified through the editor's minted correspondence, and the read-side conversion
   has its own typed refusal for a day the draft no longer carries;
8. no binding is inferred from a `ProgramDay` position / name / date / weekday / index;
9. no `workoutId` is derived from a `ProgramDayId`;
10. no legacy-schedule mapping in either direction, whole-token, and the legacy contour reaches no
    target type;
11. no UI or view-model file reaches target storage directly, and the change value holds no collaborator;
12. no DAO / entity / platform type on the revision path;
13. the revision path invokes no target pass, and the bridge's consumer list is still Stage 21's single
    entry;
14. a creation's omission is still an early return that writes nothing;
15. an existing revision's omission is `Keep`, spelled in the source and checked through the branch
    that reads the stored source forward;
16. `Clear` is one case of the change vocabulary and an empty authoring is still refused;
17. copy does not guess bindings — the creation leg reads no source, and the two copy members hold no
    target type;
18. Program identity is unchanged across a target-only revision: no new `ProgramId` is minted, and the
    reported Program is the stored one with the pointer moved.

Plus two gates that keep the previous stage's truth: the authoring value still names no legacy schedule
vocabulary, `MintedRevision` stayed private while `MintedTargetScheduleRevision` is public, and
`ProgramStructure` gained no target field.

## 16. Integration coverage

`TargetScheduleRevisionSemanticsIntegrationTest` — real SQLite, production repositories, the
production editor, the production Scheduler, the production save boundary and Stage 21's real Start
path composed exactly as `AppContainer` composes it. Only the clock, the identity generator and the
calendar are stated values.

| case | test |
| --- | --- |
| A. target-only replacement | `aTargetOnlyReplacementMintsANewRevisionAndLeavesThePreviousSourceIntact` |
| B. target-only clear | `aTargetOnlyClearMintsARevisionThatStatesNoSourceAndLeavesTheOldOneIntact` |
| C. structural + `Keep` | `aStructuralEditWithKeepCarriesTheSourceForwardReIdentifiedOntoTheNewDays` |
| D. structural + `Replace` | `aStructuralEditWithReplaceAttachesTheNewSourceToTheNewRevisionOnly` |
| E. structural with no target statement | `aStructuralEditThatSaysNothingAboutTargetSchedulingDoesNotEraseIt` |
| F. no-op + `Keep`, facts-only + `Keep` | `aNoOpEditWithKeepCreatesNoRevisionAndTouchesNothing`, `aFactsOnlyEditWithKeepCreatesNoRevisionAndLeavesTheSourceWhereItIs` |
| G. identical `Replace`, reordered `Replace` | `aTargetOnlyReplacementThatStatesTheSameSemanticsCreatesNoRevision`, `aTargetOnlyReplacementThatReordersTheSameRulesCreatesNoRevision` |
| H. source write failure | `aFailingSourceWriteRollsTheTargetOnlyRevisionBackWithIt` |
| I. fresh plan-day identities | `everyNewRevisionGetsFreshPlanDayIdentitiesAndNoOldBindingIdentityIsReused` |
| J. Start after a target-only revision | `aStartAfterATargetOnlyRevisionReadsTheNewCurrentRevisionSource` |
| K. legacy isolation | `aTargetRevisionChangeLeavesTheLegacySlotsExactlyAsTheyWere` |
| L. cross-Program isolation | `twoProgramsOwnStructurallyIdenticalTargetSourcesIndependently` |

Plus four contract cases: a creation's omission is still `Missing`; each leg refuses the other's
vocabulary; a copy without a stated authoring has no target source and guesses no binding; and a `Keep`
whose stored binding names a plan day the draft no longer carries is refused with the typed reason.

### No-op cases, explicitly

```text
structural edit + Keep        → one new Revision          (C, D)
no-op edit + Keep             → no new Revision            (F)
facts-only edit + Keep        → no new Revision            (F)
target Replace, identical     → no new Revision            (G)
```

## 17. RED mutation evidence

`scripts/program-stage22-red-mutations.sh` — nineteen rows, with the harness rules this repository has
used since Stage 12: a compile error is never a catch, a comment-only mutation proves nothing, a no-op
mutation is never a catch, every row is type-correct in the whole tree, and every mutated source is
restored byte-identically (`md5sum -c` from the repository root). KSP's incremental caches are cleared
before **every** gradle invocation, because a loop-driven recompile of the same files corrupts them and
the failure reads exactly like a source error.

The oracle is the Stage 22 behavioural and architecture suites, Stage 19's two suites, Stage 21's
behavioural suite and Stage 18's two suites — so a mutation that changed the authoring contract or the
immutable-write contract is seen by the pins that own those claims.

**Nineteen rows, not eighteen.** The brief's row 17 is *"legacy `ProgramSchedule` used to reconstruct
target source"*, and those are two different claims with two different oracles, so it is written as two
rows: one that only *reads* a legacy schedule (invisible to every behavioural suite — the read changes
no outcome — so only the gate sees it), and one that *reconstructs* a source from one and writes it at
the superseded revision (which the previous-revision-intact assertion sees). A single row could only
have been one of the two.

**Three rows are gate-only by construction**, and say so: a binding inferred from a
`ProgramDay.position` or `.name`, and the legacy-schedule *read*. Each produces exactly the same
bindings the honest conversion does, so no behavioural difference exists and only the gate can see them.
Each is written as a *second declaration in the forbidden vocabulary* — the shape a careless refactor
would actually introduce, and the shape the gate has to refuse — rather than as a retyped signature,
which would stop the test source set compiling and charge the row for the wrong reason.

**One row has no behavioural oracle at all by design.** The identity of a source is not part of its
claim, so "a target-only change mints no revision" is caught by the *outcome type* the caller is handed
(`NothingToChange` instead of `RevisionSaved`) and by the target row counts — not by any difference
between two sources that are equal. That is why the suite asserts the outcome rather than a value.

## 18. Architecture gaps recorded

These are recorded rather than designed around.

1. **The two absences are two parameters on one method.** `save` takes a creation authoring *and* an
   edit change, and refuses either on the wrong leg. Two overloads would express the split better, and
   were not used because §7's `ProgramsController` calls one `save` for both entry points and a second
   entry point would be a second place to forget the `Keep` default. The refusals make the illegal
   combinations loud rather than silent, which is the property that matters.
2. **`Malformed` and `Missing` collapse to "nothing to compare against"** on the target-only path.
   This is the one place two typed outcomes are treated alike. It is not a silent repair: a malformed
   source is never rewritten, never compared for sameness and never turned into an empty source — but a
   statement that would *repeat* it cannot be recognised as a repetition, so a revision is minted. The
   alternative was a `Failed` on a read the caller cannot repair.
3. **A `Keep` whose stored binding names a removed plan day refuses the whole structural edit.** The
   caller must state `Replace` or `Clear` instead. Refusing is honest — the alternative is dropping or
   re-pointing a binding, which is a scheduling decision made by a save boundary — but it means a
   Program cannot be edited past a certain point without revisiting its target schedule. That is a
   product decision, not a bug.
4. **`Prepare`/`save` is a two-step editor API.** `prepareTargetScheduleRevision` writes nothing and
   `saveTargetScheduleRevision` writes only the revision, because the caller has to compare before it
   commits. That is a wider surface than one method, and it is why `MintedTargetScheduleRevision` is
   public while `MintedRevision` stayed private.
5. **Stage 21's cross-boundary atomicity gap is unchanged** — the Start lifecycle write and the target
   scheduling write are still two transactions with no abstraction spanning them.

## 19. Explicitly deferred

None of the following is implemented by this stage, and none should be inferred from it:

```text
UI target authoring
persisted composition selection
persisted resolved derived sources
DerivedExcluding production source resolution
target export/import representation
full ProgramScheduler cutover
legacy slot retirement
legacy ↔ target reconciliation
indefinite-horizon maintenance policy
full target pause lifecycle policy
```

The purpose of Stage 22 is only to make target scheduling semantics correctly revisioned, so the next
UI authoring stage has a sound persistence contract to build on.
