# Program — target occurrence execution policy (§30 step 16)

## Why Phase 15 stopped at facts

Phase 15 made a target occurrence's **stored execution facts** readable:

```text
TargetOccurrenceExecutionRecord
    occurrence   PersistedTargetOccurrence   what it was
    slot         WorkoutSlot                 the opportunity
    attempts     List<WorkoutSession>        every stored attempt
                └─ SessionExercise/SetResult performed work
```

and it deliberately stopped one step short of a verdict, saying why. A slot may be attempted
repeatedly (§19), so `CANCELLED → IN_PROGRESS` and `CANCELLED → COMPLETED` are both legal stored
histories, and **no stored column says which attempt decides the occurrence's execution state**.
`ExistingOccurrence` holds a single `OccurrenceExecution`; the storage model holds a list. Something
has to bridge that gap, and Phase 15's position was that the bridge is a *policy* — so it made the
facts readable and left the policy absent, rather than inventing a precedence rule that the phase
owning it would then have to invent again.

That phase is this one.

## Why this phase owns the execution interpretation

Because a policy with no owner does not exist. The moment two callers each answer "what happened to
this occurrence?" with their own `when`, the precedence has been written down twice and the two copies
drift. The only way to keep one rule is to give it exactly one home, name it, and make every other
route to a verdict structurally inconvenient.

The home is a named object in the pure target package:

```text
TargetOccurrenceExecutionReader     reads the facts        (§30 step 15, unchanged)
TargetOccurrenceExecutionRecord     the facts themselves   (§30 step 15, unchanged)
TargetOccurrenceExecutionPolicy     decides                (this phase)
TargetOccurrenceExecutionDecision   the one result value   (this phase)
```

Reader reads, policy decides. No persistence layer, UI, presenter or runtime decides, and the
architecture gate proves it by sweeping the **whole** main source tree rather than the policy file
alone — a second place that turns stored attempt statuses into an `OccurrenceExecution` fails the
build.

**This is an explicit derived policy, not a stored fact.** Nothing in this phase is persisted, and
nothing here should ever be read as something the database held. The stored facts are Phase 15's;
this phase's contribution is the rule that interprets them.

## The precedence matrix

The rule is monotonic — the occurrence's execution is the **highest execution state any of its
attempts ever reached**. It is not "the state of the latest attempt", and it is not "the worst state"
either.

| Stored attempts (in any order) | Execution | Why |
| --- | --- | --- |
| none | `PLANNED` | the occurrence is still ahead of the user; this is the normal state of a planned workout, not an absence |
| all `CANCELLED` | `CANCELLED` | nothing was ever started to a finish, and nothing was left open |
| ≥1 `COMPLETED` | `COMPLETED` | the highest state is a *maximum*, and a later attempt cannot undo an earlier one |
| ≥1 `IN_PROGRESS`, no `COMPLETED` | `STARTED` | a workout of this occurrence is open right now |

The branch order is load-bearing, not stylistic. `COMPLETED` is tested **before** `IN_PROGRESS`,
because a completed attempt beside a still-open one is a completed occurrence: the open one is a
*different, later* attempt of the same slot, and "at most one `IN_PROGRESS`" is enforced by the start
transaction, not by the data.

The full matrix, as tested:

| # | Stored history | Execution |
| --- | --- | --- |
| 1 | *(empty)* | `PLANNED` |
| 2 | `IN_PROGRESS` | `STARTED` |
| 3 | `CANCELLED` | `CANCELLED` |
| 4 | `COMPLETED` | `COMPLETED` |
| 5 | `CANCELLED` → `IN_PROGRESS` | `STARTED` |
| 6 | `CANCELLED` → `COMPLETED` | `COMPLETED` |
| 7 | `CANCELLED` → `IN_PROGRESS` → `COMPLETED` | `COMPLETED` |
| 8 | `COMPLETED` → later `CANCELLED` | `COMPLETED` |
| 9 | `COMPLETED` → later `IN_PROGRESS` | `COMPLETED` |
| 10 | `CANCELLED` + `IN_PROGRESS` (mixed) | `STARTED` |
| 11 | `CANCELLED` + `COMPLETED` (mixed) | `COMPLETED` |
| 12 | `IN_PROGRESS` + `COMPLETED` (mixed) | `COMPLETED` |
| 13 | `CANCELLED` × 3 | `CANCELLED` |
| 14 | `CANCELLED` + `CANCELLED` + `COMPLETED` | `COMPLETED` |

Rows 8 and 9 are the ones that justify the word *monotonic*. An occurrence does not fall back: a
later cancellation is a second fact about a second attempt, not a retraction of the finished workout.

## Why `SlotStatus` remains separate

`WorkoutSlot.status` is an **opportunity fact**, and none of its four values is an execution state:

| `SlotStatus` | what it is | why it is not an `OccurrenceExecution` |
| --- | --- | --- |
| `PLANNED` | the opportunity is still ahead | not evidence that no historical session exists |
| `COMPLETED` | a session took the opportunity | a **different token** from `SessionStatus.COMPLETED` |
| `MISSED` | the date passed without a workout | not a cancellation — a workout may still be trained late (§19) |
| `SUPERSEDED` | a later plan withdrew the opportunity | about the *plan*, not the workout |

So `WorkoutSlot.status` is **not an input to the precedence at all**. It is carried into
`TargetOccurrenceExecutionDecision` as its own separately-typed field `slotStatus`, which is what
lets a caller ask about the opportunity without ever being handed it as the verdict:

```kotlin
data class TargetOccurrenceExecutionDecision(
    val execution: OccurrenceExecution,   // the verdict, declared not computed
    val slotStatus: SlotStatus,           // the opportunity outcome, its own concept
    val attemptCount: Int                 // zero vs. filtered-away, visibly
)
```

The suite stores every disagreement as a case: `MISSED` + cancelled → `CANCELLED`; `MISSED` +
in-progress → `STARTED`; `MISSED` + completed → `COMPLETED`; `SUPERSEDED` with each attempt status
→ identical to `PLANNED` with the same attempt; `PLANNED` + completed historical attempt →
`COMPLETED`; `COMPLETED` slot + later cancelled attempt → `COMPLETED`; and a `COMPLETED` slot whose
only attempt was cancelled → `CANCELLED`. The last one is the sharpest: the opportunity went one way
and the execution went another, and the decision reports both without merging them.

## Why no attempt selection is needed for the verdict

The obvious alternative to a precedence is to pick a *representative* attempt — the latest, the
first, the one with the "best" status — and let its own status be the occurrence's. The policy does
none of these, for a substantive reason: **a maximum needs no representative.**

`COMPLETED` beats `STARTED` beats `CANCELLED` beats *(absent)*. Once the state is defined as the
highest one attained, the question "which attempt decides?" dissolves into "does *any* attempt hold
this status?" — and a `any`/`all` over the whole list is order-free by construction.

The rejected alternatives are each individually wrong, and the suite's history cases are what show
it:

* **latest attempt** — a `COMPLETED` → `CANCELLED` history reads as `CANCELLED`, i.e. the occurrence
  loses a workout it actually did;
* **first attempt** — a `CANCELLED` → `COMPLETED` history reads as `CANCELLED`, i.e. the occurrence
  never gets credit for a later success;
* **"worst" status** — a single cancelled attempt beside a completed one reads as `CANCELLED`, which
  is the same error in a different guise;
* **unanimity (`all { COMPLETED }`)** — a completed occurrence with a later open attempt reads as
  `STARTED`, demoting a finished workout.

Because the verdict depends only on the *set* of stored statuses, **stored order cannot change it** —
and that is asserted exhaustively, over all six permutations of a three-attempt history, rather than
by a single reversed-order spot check.

## Why `ActualResult` mapping is explicitly deferred

The policy produces one value: an execution state. It does **not** produce performed work, and
nothing in this phase is allowed to:

* no `ActualResult` and no `PerformedWork` is constructed (architecture gate 8 asserts both as absent
  whole tokens);
* no `workId ← exerciseId` mapping is invented — no documented contract relates a session's
  `exerciseId` to a target occurrence's `workId`, and the components in `PersistedTargetOccurrence`
  are rule/workout identities, not session exercises;
* a session's sets are never summed into one `ActualResult`. A sum is a *rule* about how several
  reps relate to one planned target, and that rule is exactly as unowned today as this phase's
  precedence was yesterday;
* no `ExistingOccurrence` is constructed. Doing so would require a *complete* result — execution
  **and** actuals — and this phase supplies only the first half. Half an `ExistingOccurrence` is
  worse than none, because it invites a caller to fill in the other half themselves.

**Mapping performed work onto planned work remains an explicitly later, separately owned phase.**
Until that phase exists, `TargetOccurrenceExecutionRecord.confirmedSets()` and `skippedExercises()`
are the only way to read what was performed, and they return the session's own stored graph.

## Architecture ownership

The policy is a pure value in `domain/program/target`, beside the record it consumes, for the same
reason Phase 14 and Phase 15 put their files there: it is schedule *semantics* over already-read
data, with no storage, no clock and no id generator. Unlike the read-back value it **does** hold a
verdict — which is precisely why it is a *named owner* rather than an incidental member of a record,
a repository or a presenter.

Pinned file lists: adding `TargetOccurrenceExecutionPolicy.kt` extends the eight
`domain/program/target` file censuses, in one pass, each naming the new file and why it belongs
there. The lists stay **closed** — no assertion was removed and no list was opened, and each list's
closing comment moved from "a tenth file still fails here" to "an eleventh file still fails here" so
the claim stays true rather than merely satisfiable.

**Unchanged by this phase:** `WorkoutSessionRepository`, `ProgramScheduleRepository`,
`TargetScheduleOccurrenceRepository`, slot identity, target-occurrence persistence, session
persistence, `SessionRuntime`, `ProgramProgressService`, the legacy scheduler, `ExistingOccurrence`,
and `TargetSchedulePolicy`. No Room table, migration, DAO or repository was added, and no wiring was
changed — the policy has no production caller yet, which is correct: a phase that defines an
interpretation is complete before something renders it.

## Failure and non-repair behaviour

The policy is not a validator and does not behave like one. It relies on Phase 15's record
invariants, which refuse corrupted stored data in the record's own constructor — a slot presenting
another target key, a slot of another Program, an attempt at another slot or of another Program, and
a session listed twice — **before** the policy is reached. A malformed session graph is propagated
from the session repository's own validation, not swallowed.

What the policy explicitly does not do:

| It does not | because |
| --- | --- |
| filter a status out before deciding | dropping one changes the answer; the count in the decision shows whether a `PLANNED` came from zero attempts or a hidden one |
| reorder, deduplicate or sort attempts | the record's stored order is stored order |
| repair, default or reinterpret a status it does not recognise | that is a write path's job, not a read-and-decide path's |
| consult the slot, the date, the revision or the occurrence key | none of them is a source of execution (§ above) |
| return a verdict for a record it could not build | a refusal is Phase 15's, and it propagates unchanged |

A duplicate `sessionId` listed twice is refused by the record's invariant as an
`IllegalArgumentException` — the suite asserts that, and asserts the policy is never reached with
one. Silently deduplicating would have been the "helpful" repair, and it would have turned a
corrupted store into a confident verdict.

## Architecture gates

`TargetOccurrenceExecutionPolicyArchitectureTest` — ten claims, each asserted as a token or a shape
in real code with comments stripped first (so these files can explain the absences in prose without
tripping the rules about them):

1. the policy is in the target domain package and is this phase's whole production surface;
2. it has **no repository dependency** — it asks nobody for anything;
3. it has **no DAO / Room / `AppDatabase` / transaction** access, and every import it has is a pure
   domain value;
4. it has **no clock, `IdGenerator` or randomness**;
5. it depends on **no planner, resolver, composer, reconciler, presenter, materializer, persister,
   legacy scheduler or runtime**;
6. it does **not read `ProgramDay` or the current `ProgramRevision`** — the record type is its only
   parameter;
7. it does **not parse the occurrence key** (and never reaches for it at all);
8. it constructs **no `ActualResult`**, `PerformedWork`, `SetResult` or `ExistingOccurrence`;
9. it introduces **no execution precedence outside the single policy owner** — the sweep runs over
   the *whole* main source tree and requires the policy to be the only file that both reads attempt
   statuses and builds an `OccurrenceExecution` value; it also requires exactly one `fun` and exactly
   one `val execution = when {`;
10. it is **pure over the Phase 15 record** — no `filter`, `sorted`, `distinct`, `reversed`,
    `firstOrNull`, `lastOrNull`, `maxBy`, `finishedAt`, `completedAt`, `plannedFor` or `attemptIds`
    anywhere in it, plus no mutable singleton state and a value type referencing pure domain types
    only.

**Exact token / shape guards**, whole-token matched with `_` as a word character, because a plain
`contains` scan cannot separate this phase's own names from a forbidden one: `OccurrenceExecution`
occurs inside `TargetOccurrenceExecutionPolicy` and `TargetOccurrenceExecutionDecision`. A gate that
fired on either would have to be weakened to pass, which is how a gate stops being a gate.

## Verification

* `TargetOccurrenceExecutionPolicyTest` — **33 cases**: the empty attempt list; one
  `IN_PROGRESS` / `CANCELLED` / `COMPLETED`; `CANCELLED` → later `IN_PROGRESS`; `CANCELLED` → later
  `COMPLETED`; `CANCELLED` → `IN_PROGRESS` → `COMPLETED`; `COMPLETED` → later `CANCELLED`; `COMPLETED`
  → later `IN_PROGRESS`; the three mixed pairs; three cancelled attempts; two cancelled plus a
  completed one; **all six permutations** of a three-attempt history giving an identical decision,
  and the same for a two-attempt history; `MISSED` + cancelled / in-progress / completed / no attempt;
  `SUPERSEDED` with no attempt and with each of the three statuses, each equal to the `PLANNED`-slot
  reading; `PLANNED` + completed historical attempt; `COMPLETED` slot + later cancelled attempt;
  `COMPLETED` slot whose only attempt was cancelled; slot status and execution as two separately
  typed fields; no representative attempt (3 attempts in, `attemptCount` 3 out); the record's order
  untouched; a duplicate session refused by the record's own invariant as
  `IllegalArgumentException`; equal records giving equality-identical decisions; a repeated read
  changing nothing; the execution being a declared property; and a collaborator-free object leaving
  no state between calls.
* `TargetOccurrenceExecutionPolicyArchitectureTest` — **13 cases**: the ten gates above, plus the
  no-mutable-state check, the value-type dependency check, and the assertion that Phase 15's record
  still holds a list of every attempt and still exposes no verdict of its own.
* Full JVM suite, `:app:compileDebugKotlin`, `:app:compileReleaseKotlin`, `:app:assembleDebug`:
  green — see the commit message for the exact census.
* `git diff --check`: clean.
* The test census was cross-checked against the source `@Test` annotations, as in Phase 15.

No test in either suite implies that a slot status is an execution state; the suite asserts the
opposite in every slot-status case, and one case exists specifically to show the two disagreeing.

### The RED mutation gate

`scripts/program-stage16-red-mutations.sh` — 18 mutations, each a deliberate break of the
precedence, each required to fail a real oracle:

| # | mutation | caught by |
| --- | --- | --- |
| 1 | empty attempts → `CANCELLED` | behaviour |
| 2 | empty attempts → `STARTED` | behaviour |
| 3 | `CANCELLED` → `STARTED` | behaviour |
| 4 | `CANCELLED` → `COMPLETED` | behaviour |
| 5 | `STARTED` → `COMPLETED` | behaviour |
| 6 | `COMPLETED` → `STARTED` | behaviour |
| 7 | `COMPLETED` → `CANCELLED` | behaviour |
| 8 | use `slot.status` as the execution | behaviour |
| 9 | derive from `finishedAt` | architecture + behaviour |
| 10 | ignore `CANCELLED` attempts | architecture + behaviour |
| 11 | ignore `IN_PROGRESS` attempts | architecture + behaviour |
| 12 | choose only the last attempt | architecture + behaviour |
| 13 | choose only the first attempt | architecture + behaviour |
| 14 | require every attempt to be `COMPLETED` | behaviour |
| 15 | treat a `MISSED` slot as `CANCELLED` | behaviour |
| 16 | treat a `SUPERSEDED` slot as `CANCELLED` | behaviour |
| 17 | derive the state from the planned date | architecture + behaviour |
| 18 | derive the state from the current revision | architecture + behaviour |

Harness properties, all required and all enforced:

* **every anchor is preflighted** before the baseline runs — an anchor absent beforehand, or a
  mutation already present beforehand, aborts the run;
* **a compile error is never a catch.** A mutation that fails to compile was seen by no oracle, so
  nothing was proven about the rule; it is reported as `NOT A CATCH` and charged as a failure, which
  forces the row to be rewritten as type-correct code at the layer the oracle reads;
* **a comment-only mutation is rejected before the suites run** — the probe strips comments and
  asserts the mutated line survived as real code, since every gate strips comments first;
* the source is restored **byte-identically** and proven so with `md5sum -c` after the last row;
* the run exits nonzero if any mutation was missed or the restoration failed.

Result: `caught: 18`, `missed: 0`, `source restored byte-identically`, `exit 0`.
