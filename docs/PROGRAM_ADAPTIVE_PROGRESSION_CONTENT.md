# PROGRAM ADAPTIVE — PROGRESSION RELATION CONTENT (P32)

> Stage P32 — production progression-relation **content** and **activation**.
>
> ```text
> P31:  catalogue exists, content empty, provider unwired
> P32:  catalogue exists, four ladders authored, provider wired,
>       two known families deliberately remain undeclared
> ```

---

## 1. What P32 is

P31 created the persisted, app-owned, family-level progression **catalogue** — the table
`progression_relation_variant`, the `ProgressionRelationRepository` that reads and writes it, and a
`StoredProgressionRelationProvider` that projects it — and then deliberately left it **empty and
unwired**. Production wired `NoDeclaredProgression`, so every family answered *"no ladder is declared"*
and every adaptive pass stopped at `NO_DECLARED_PROGRESSION_RELATION`.

P32 supplies the missing **data** and the missing **wiring**, and nothing else. The engine, the policy,
the guard rules, the confirmation windows, recovery semantics, user-authored and pinned protection,
target-slot selection, generation, scheduling and the UI are untouched.

---

## 2. The audited domain invariant — and why the P31 primary key stays

Before authoring anything, P32 audited the existing domain contract and found this to be a **deliberate
rule, not an accident**:

```kotlin
// ProgramProgressionRelation.init
val duplicates = variants.map { it.exerciseId }.groupingBy { it }.eachCount().filter { it.value > 1 }
require(duplicates.isEmpty()) {
    "an exercise is one position in its family's hierarchy, found twice: ${duplicates.keys}"
}
```

**One exercise id is one position in one family's hierarchy.** `declared(exerciseId)` returns a *single*
variant, and it is the engine's primary lookup — `levelOf`, `realignment`, and five sites in
`ProgramAdaptiveIntegration` all resolve *an exercise id to its level* through it.

Therefore P31's storage identity is a **faithful projection** of the domain, and P32 keeps it:

```text
PRIMARY KEY(familyId, exerciseId)
```

The consequence shapes the whole stage: **a family can only declare as many rungs as it has distinct
exercises to declare.** That is why three of the four authorised ladders ship four rungs (`-1..+2`) and
only `pullups` ships five (`-2..+2`).

> An earlier P33 proposal tried to widen this key to `(familyId, level, exerciseId)` so that one exercise
> could occupy several levels. It was **audited and stopped**: the domain already forbids that shape, so
> widening the key alone would not have let such a ladder round-trip — the repository hands stored rows
> to the same constructor, which throws on read. That investigation was abandoned rather than implemented.

---

## 3. The four authorised ladders

All levels are contiguous within each relation; a relation's own lowest and highest level are its floor
and ceiling. Nothing here requires `-2..+2`.

### `pushups` — four rungs, `-1..+2`

| level | exercise | dimension | per-set targets |
| ----- | -------- | --------- | --------------- |
| `-1` | `pushups_knee` | `REP_BASED` | `[12, 12, 12]` |
| `0` | `pushups` | `REP_BASED` | `[8, 8, 8]` |
| `+1` | `pushups_wide` | `REP_BASED` | `[7, 7, 7]` |
| `+2` | `decline_pushups` | `REP_BASED` | `[7, 7, 7]` |

Volume **falls** as the exercise gets harder (`12 → 8 → 7 → 7`). That is intentional: a harder variation
is prescribed fewer repetitions, and the content states each rung's own target rather than computing it
from a shared rule, so the inversion is a visible decision rather than one buried in a formula.

### `squats` — four rungs, `-1..+2`

| level | exercise | dimension | per-set targets |
| ----- | -------- | --------- | --------------- |
| `-1` | `squats_sumo` | `REP_BASED` | `[14, 14, 14]` |
| `0` | `squats` | `REP_BASED` | `[15, 15, 15]` |
| `+1` | `cossack_squat` | `REP_BASED` | `[10, 10, 10]` |
| `+2` | `squats_jump` | `REP_BASED` | `[12, 12, 12]` |

### `lunges` — four rungs, `-1..+2`

| level | exercise | dimension | per-set targets |
| ----- | -------- | --------- | --------------- |
| `-1` | `lunges_reverse` | `REP_BASED` | `[10, 10, 10]` |
| `0` | `lunges` | `REP_BASED` | `[10, 10, 10]` |
| `+1` | `step_ups` | `REP_BASED` | `[10, 10, 10]` |
| `+2` | `lunges_side` | `REP_BASED` | `[10, 10, 10]` |

Three rungs share `[10, 10, 10]` deliberately. Equal volume across distinct exercises is not a defect —
it is the authored target, written out per rung rather than hoisted into a shared constant, so a later
edit to one rung cannot silently move another.

### `pullups` — five rungs, `-2..+2`

| level | exercise | dimension | per-set targets |
| ----- | -------- | --------- | --------------- |
| `-2` | `hang` | **`TIME_BASED`** | `[30, 30, 30]` |
| `-1` | `pullups_chin` | `REP_BASED` | `[6, 6, 6]` |
| `0` | `pullups` | `REP_BASED` | `[5, 5, 5]` |
| `+1` | `pullups_neutral` | `REP_BASED` | `[5, 5, 5]` |
| `+2` | `pullups_wide` | `REP_BASED` | `[4, 4, 4]` |

**The time → repetition transition is intentional and survives the whole round trip.** The bottom rung is
a dead hang — a supported hold, not a repetition — and every rung above it is a repetition-based pull-up.
The domain models prescription dimension as a property of a *rung* rather than of a *family*, which is
exactly what lets this exist with no special case. The dimension is never used to *compare* these rungs
(nothing adds a hang's seconds to a pull-up's repetitions).

**Seventeen rows total: 4 + 4 + 4 + 5.**

---

## 4. What is deliberately excluded, and why

| excluded | reason |
| -------- | ------ |
| `pushups_military` | A real `pushups` family exercise, deliberately **not** a rung. Placing it would mean asserting where it sits relative to `pushups_wide` and `decline_pushups`, and there is no independent evidence here for that ordering. Filling a position to look complete is how a fabricated ordering enters a system. |
| `deep_squat` | A mobility **hold**, not a squat variation that is easier or harder than another. §15's adaptation vocabulary is about progression along one family's variant axis, and a hold is not on it. |
| `side_plank` | A different hold, not an ordered rung of a `plank` ladder. |
| `plank` (whole family) | See below. |
| `glute_bridge` (whole family) | See below. |

### Why `plank` and `glute_bridge` stay undeclared

`plank`'s intended progression is a five-step **time** ladder — `20 / 25 / 30 / 35 / 40` seconds — and
`glute_bridge`'s is a five-step **volume** ladder — `11 → 19` repetitions. Both require *the same exercise
at several levels*, which the audited domain invariant rejects (§2). Neither can be authored honestly
today.

They therefore stay:

```text
relationOf("plank")        == null
relationOf("glute_bridge") == null
```

**and no one-rung "ladder" is manufactured for either.** A single-rung relation is not a harmless
placeholder: it makes the engine hold at *both* the floor and the ceiling, which is a different claim
("this family declares a hierarchy") reached by hiding the absence rather than by declaring anything.
Twenty-four of the catalogue's twenty-eight families are undeclared for the same reason — content was
authored only where it could be authored honestly.

These two remain open **content/model questions** for a future stage, and are deliberately not solved
here. Two routes exist and neither is this stage's call: author genuinely distinct plank/bridge variants
as separate exercises, or revisit the identity model with its own authorisation.

---

## 5. Why the content is DATA

The content source is `ProductionProgressionRelationDefinitions` — one immutable file holding the four
relations as literals. It contains no `when`, no branch over categories or subcategories, no catalogue
lookup, no `Exercise` metadata read, no difficulty coefficient, no plan phase, no adaptive history, and
no reference to the retired Stage-1 pilot. Every level, exercise id and per-set target is **written
out**.

Each prescription is the domain's own `RepPrescription` or `TimePrescription` — no second prescription
model, and no flattened scalar. `[8, 8, 8]` is three sets of eight, which is a different prescription
from one set of twenty-four and cannot be recovered from a total.

The architecture suite asserts each of those absences against the production source, so "this file is
data" is a checked property rather than an intention.

---

## 6. Bootstrap lifecycle

```text
ProductionProgressionRelationDefinitions   the authored content
            ↓
BuiltInProgressionCatalogueBootstrap        Application.onCreate
            ↓
progression_relation_variant                P31's table, schema 18, unchanged
            ↓
ProgressionRelationRepository
            ↓
StoredProgressionRelationProvider
            ↓
ProgramAdaptiveIntegration
            ↓
ProgramAdaptiveEngine
```

### Where initialization happens, and why both fresh and existing databases are covered

The bootstrap runs from **`Application.onCreate`**, beside the Standard Program bootstrap, and it is the
only place seeded content enters the catalogue. The table itself is created by P31's `MIGRATION_17_18`,
which ships **no rows**, so:

* a **fresh** install creates the table through the same migration chain and is then seeded here;
* an **existing** database already at schema 18 is opened unchanged — **P32 adds no migration**, because
  content is not schema — and is then seeded here too.

Both cases converge on one call site, which is what makes them indistinguishable from the bootstrap's
point of view: it never asks which it is, only what is already there.

### How ordering is guaranteed — structurally, not by timing

Two facts do the work, and the second is the load-bearing one:

1. **The provider never seeds.** `StoredProgressionRelationProvider` is a pure read. There is no branch in
   it that can write and no lazy initialisation, so a consumer that reads *early* sees an honestly empty
   catalogue rather than a race against a seed.
2. **The adaptive pass is not reachable until a Session completes.** `ProgramAdaptiveIntegration` runs
   from `sessionRuntime.finishSession` — a user action minutes into the app's life — while the bootstrap
   runs in `onCreate`. Ordering therefore does not rest on the seed being fast; it rests on no consumer
   existing yet.

### Idempotence, and what is never overwritten

The bootstrap asks the repository whether each family is **already declared** and stores it only if not.
So:

* running it three times stores one ladder once;
* an existing ladder is **never overwritten**, so a future user-authored ladder for an authorised family is
  preserved rather than replaced by the shipped definition;
* no family outside the four can be seeded, because the input list *is* the four authorised families —
  there is no separate seed scope to widen by accident;
* the check is **per family**, so a partially-lost catalogue is *completed* rather than skipped.

**A deleted ladder is not silently restored by a read or an adaptive evaluation.** The provider would
answer `null` and the integration would report `NO_DECLARED_PROGRESSION_RELATION` for that family. It is
restored only at the next application start — a deliberate policy: authored built-in content is
app-owned and re-established at startup, while nothing a consumer observes is quietly rewritten underneath
it.

---

## 7. Provider activation, and why the port is `suspend`

`AppContainer` now wires `relations = storedProgressionRelationProvider`; `NoDeclaredProgression` is no
longer what production passes. The provider holds exactly **one** collaborator — the repository — and
reads nothing else: not the authored definitions, not the shipped catalogue, not adaptive history. The
four ladders reach production **through the rows**, which is what keeps the persisted catalogue
authoritative rather than decorative; a provider that also read the static source would hold two answers
to "what does this family declare", and they could disagree the moment a stored ladder was replaced.

**The port is now `suspend`:**

```kotlin
suspend fun relationOf(familyId: String): ProgramProgressionRelation?
```

P31 declared it synchronous and bridged the suspending repository read with `runBlocking`, recording that
as an explicit gap it did not own. P32 is the correct owner to close it: the production caller
(`ProgramAdaptiveIntegration`) is already suspending end to end, so the port can express what the call
actually costs. The answer comes from **storage**, and a port that cannot express a database read forces
its implementation to hide one behind a blocking call.

There is now **zero `runBlocking`** anywhere on the progression-relation provider path — asserted by
architecture gate, not by convention.

---

## 8. What production adaptive can now do

For a **completed Session** whose family is one of the four authorised ones, a production pass now reaches
`ProgramAdaptiveEngine` with a real declared relation:

* family classification — `PRESENT` (P30, from the shipped catalogue);
* declared progression — `PRESENT` (P32, from the persisted catalogue);
* current exercise — declared by that ladder;
* prescription — explicit, per set, exact;
* allowed exercise set — explicit.

and can therefore return the engine's **existing** progression result when the **existing** policy
conditions are satisfied, with the authored next rung and its authored prescription.

P32 changed the **source** of the `ProgramProgressionRelation` only. It changed no threshold, no decision
order, no guard, no confirmation window, no recovery rule, no ownership rule and no target-slot rule.

---

## 9. Remaining content / model questions

1. **`plank`** — a five-step time progression needs one exercise at several levels. Either author
   genuinely distinct plank variants, or revisit the identity model under its own authorisation.
2. **`glute_bridge`** — the same, for volume. It has exactly one exercise in its family today.
3. **The other 22 undeclared families** — no ladder authored; `relationOf` answers `null`.
4. **User-authored ladders** — the bootstrap is absent-only by design, so a future editor writing through
   the same repository will not be overwritten by the shipped definitions. No such editor exists.

---

## 10. Verification

| claim | where it is proven |
| ----- | ------------------ |
| the four ladders, verbatim, through Room | `ProductionProgressionCatalogueTest` |
| 17 rows, exact per-family counts, canonical order, no leaks | `ProductionProgressionCatalogueTest` |
| `plank` / `glute_bridge` stay undeclared; 24 families undeclared | `ProductionProgressionCatalogueTest` |
| a deleted ladder is not restored by a read | `ProductionProgressionCatalogueTest` |
| fresh / existing-v18 / repeated / partial bootstrap; reopen does not duplicate | `BuiltInProgressionCatalogueBootstrapTest` |
| schema stays 18; no migration added; primary key unchanged | `BuiltInProgressionCatalogueBootstrapTest` |
| the stored relation drives a real progression, ceiling, floor, availability, ownership | `ProductionAdaptiveProgressionContentTest` |
| content is data; one source; four families; exclusions; bootstrap scope; provider shape; no `runBlocking`; wiring | `ProductionProgressionContentArchitectureTest` |
| every P32 rule is load-bearing | `scripts/program-stage32-red-mutations.sh` |