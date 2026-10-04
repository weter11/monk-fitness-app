# PROGRAM_ADAPTIVE_PROGRESSION_RELATIONS

**Stage:** P31 — production progression-relation persistence boundary
**Base:** `a6cc238` (merge of P30)
**Branch:** `feat/program-stage31-progression-relation-persistence`

---

## 1. What this stage is

P30 closed the first of two production gaps:

```text
NO_FAMILY_CLASSIFICATION
        ↓
NO_DECLARED_PROGRESSION_RELATION
```

The app can now name the family it is about. What it still cannot do is say **which exercises that
family may progress between** — because the target tree stored no ladder anywhere, and
`ProgressionRelationProvider` had nothing behind it but `NoDeclaredProgression`.

P31 closes exactly one layer of that: **it makes a progression relation a real persisted,
app-owned artefact.** It adds the storage boundary, the repository that reads it, and the production
provider that projects it.

It deliberately does **not** add ladder content, and it does **not** change what production does.

```text
family classification     PRESENT      (P30)
progression catalogue     PRESENT      (P31 — this stage)
catalogue content         EMPTY        (nothing is authored yet)
production provider       NOT WIRED    (deliberately — see §7)
adaptive result           NO_DECLARED_PROGRESSION_RELATION   (unchanged)
```

## 2. A relation is an app-owned, family-level, persisted definition

A `ProgramProgressionRelation` is keyed by `familyId` and by nothing else, and this stage's whole
schema follows from that.

**It is a definition, not history.** A ladder says which variants exist, at which positions,
prescribing what. It is authored once and read by every plan that trains that family. That is a
different lifetime from `program_family_progression_state`, `program_adaptive_decision_record` and
`adaptive_adjustment`, which record *where a family currently is inside one revision* and *what was
decided once*. Conflating them would give one class two unrelated lifetimes — an immutable global
catalogue and a per-revision mutable trail — and would make "the ladder is not adaptive history"
unverifiable. So the stage adds a **separate** `ProgressionRelationRepository` and leaves
`ProgramAdaptiveRepository` owning exactly the three tables it already owned.

**It is global rather than Program-scoped, and that is a domain decision, not a storage convenience.**
`ProgramProgressionRelation`'s own KDoc states that a `level` is "an ordinal position **inside this
family's hierarchy and nowhere else**", that the architecture "defines no universal difficulty
mathematics that would make one family's level comparable to another's", and that the type carries no
`programId` or `revisionId`. A revision-scoped table would restate one family's ladder per revision and
invite exactly the cross-family comparison the domain forbids — two revisions of the same Program
disagreeing about what "level 2" means. `ProgressionRelationProvider.relationOf(familyId)` is keyed
by family alone for the same reason.

Consequently the new table declares **no foreign key at all**: there is no family catalogue in this
schema, so a key could only have pointed at a Program or a revision. Its absence is the schema stating
the scope rule, and `everyTargetEntityStandsOnTheOwnershipGraph` was **revised, not relaxed** to name
it as the second deliberate exception alongside `app_state`.

## 3. The persisted row: one normalized row per variant

```text
progression_relation_variant

familyId                 TEXT     NOT NULL
level                    INTEGER  NOT NULL
exerciseId               TEXT     NOT NULL
prescriptionDimension    TEXT     NOT NULL
perSetTargets            TEXT     NOT NULL

PRIMARY KEY(familyId, exerciseId)
```

**One row per declared rung.** A ladder is a *set* of rows rather than one serialized value, and that
is what makes ordering, contiguity and per-rung identity answerable by the database instead of by
parsing a string. There is no `ladderJson`, no `variantsJson`, no `levelsJson`.

**The row carries nothing else.** Deliberately absent: `programId`, `revisionId`, any current state, any
adaptive outcome, any policy, any timestamp, any ranking, any difficulty or inferred-difficulty score,
and any "enabled" flag. This is catalogue/configuration; adaptive history lives in
`ProgramAdaptiveRepository`'s tables.

**The key makes one rule a table constraint.** `(familyId, exerciseId)` means *one exercise cannot be
declared twice in one family* is structural rather than a caller convention — a second row for the same
pair cannot be inserted at all. It is deliberately **not** `(familyId, level)`: two variants at one
level are a legitimate declaration (§15's same-level shape), and a uniqueness constraint there would
refuse the domain's own vocabulary.

**No index is declared.** `familyId` is the key's leading column and the only column the table is ever
read by, exactly as `program_target_schedule_rule` declares none. SQLite's automatic
`sqlite_autoindex_*` entry for the composite key is the key itself, not a declared index.

**Contiguity is not expressible per row**, so it stays the domain's rule: the repository assembles the
stored rows through `ProgramProgressionRelation`'s own constructor, which performs the
duplicate-exercise, contiguity and canonical-order checks. The persistence layer contributes no rules
of its own and repairs nothing.

## 4. The prescription is stored losslessly

A variant's prescription is part of the ladder definition: §15's adaptation is a *presentation*
(`before` → `after`), so a rung that stored no prescription could not be presented, and the engine would
have to keep the current one — i.e. claim that a harder variant prescribes exactly what an easier one
did.

So `perSetTargets` is the **full ordered list**, carried by the schema's **existing**
`ProgramTypeConverters.toPerSetTargets` / `fromPerSetTargets` pair. P31 introduces no new converter
semantics, and the architecture gate asserts that exactly one converter file handles that shape.

```text
RepPrescription([12, 10, 8, 6])  ->  REP_BASED   "12,10,8,6"  ->  RepPrescription([12, 10, 8, 6])
TimePrescription([30, 30, 45])   ->  TIME_BASED  "30,30,45"   ->  TimePrescription([30, 30, 45])
```

`[12,10,8,6]` and `[10,10,10,10]` are two different prescriptions that happen to share a set count.
Storing an ordered target list in one `TEXT` column is this schema's established, already-proven
representation — that is **not** a serialized ladder blob, and the normalization gate deliberately does
not forbid it. What is forbidden is a column holding *the ladder itself* as one value.

The stored **dimension** decides the subtype on the way back, never the shape of the target list, so
the same four numbers can never come back as the other dimension's unit. `SET_BASED`,
`DIFFICULTY_BASED` and `REST_BASED` are named in the domain with no `Prescription` subtype yet; a row
naming one is **refused** on read rather than defaulted to the nearest implemented dimension.

## 5. No automatic seed

`MIGRATION_17_18` creates the table and executes **exactly one statement**. There is no `ALTER`, no
`INSERT`, no `UPDATE`, no `DELETE`, no `RENAME` and no backfill, and
`EXPECTED_PROGRESSION_RELATION_STATEMENTS` is a one-element list on purpose.

Nothing in an upgraded database could be converted into a ladder honestly:

- `program_exercise` holds what one revision's plan prescribes — a single rung of a family, or none;
- `program_family_progression_state` holds where a family *currently* is — history, not a definition;
- `program_adaptive_decision_record` holds what was decided once — a decision is not a ladder.

A seed would write a claim about what a family trains that no author ever made. **An empty catalogue
after migration is the correct state, not a gap to be filled later by inference.**

## 6. No default ladder, and the Stage-1 pilot stays forbidden

`ProgressionRelationRepository.relationOf` answers `null` for a family the catalogue does not declare.
That is the honest reading of an app that has authored no ladder. It is deliberately **not**:

- an empty relation (`ProgramProgressionRelation` refuses one — an empty variant list is not a ladder);
- a fabricated single-rung relation;
- a fallback family;
- a level computed from a catalogue position, a plan-day phase, an exercise name or a
  `WorkoutGenerator` difficulty coefficient;
- `FamilyProgressionState.currentExerciseId` read as a hierarchy (a current position is ONE rung);
- adaptive decisions read as a ladder (what a family did last window is not what it may do next);
- the retired Stage-1 `PilotProgressionProfiles`, which belong to the previous generation and are
  scoped to the legacy program's own axis.

Every entry on that list is pinned mechanically by
`ProgressionRelationPersistenceArchitectureTest.theCataloguePathReadsNoLadderAdjacentSource`, which
scans the whole catalogue path (entity, DAO, mapper, repository, provider) for each of those tokens in
comment-stripped source.

`StoredProgressionRelationProvider` holds **exactly one collaborator** — the catalogue repository —
asserted by reflection on its declared fields, because a second field is the only place a default
ladder, a fallback family or a clock could hide.

## 7. Production still refuses, and why the provider is not wired

```text
no persisted relation
        ↓
NO_DECLARED_PROGRESSION_RELATION
```

`AppContainer` is **byte-identical to its state before this stage** and still wires
`relations = NoDeclaredProgression`.

That is a deliberate decision, not an omission. The catalogue is created empty, so wiring
`StoredProgressionRelationProvider` in its place would change **no observable behaviour** while adding
a node to the composition root and pulling a database read into the adaptive pass. P31's goal is the
authoritative storage boundary; pretending a ladder exists by reading an empty one would be exactly the
fabrication this stage refuses.

Two gates hold the deferral in place: the architecture test asserts the container still wires
`NoDeclaredProgression` **and** that neither the provider nor the catalogue repository appears in it,
while `AppDatabase` does declare the DAO the catalogue is persisted through.

**Recorded as an architecture gap for the next stage:** `ProgressionRelationProvider.relationOf` is
not `suspend`, so the production provider bridges to the repository's suspending read with
`runBlocking`. That is the lesser of two evils *for this stage* — changing the port would alter a domain
interface the adaptive engine and its tests already depend on, which P31 does not own. The bridge
performs one indexed primary-key lookup per call, so the block is bounded by a single row read. The port
should become `suspend` when its caller is an integration that is already suspending.

## 8. Production ladder content is a separate later stage

**This stage creates the place where a ladder can exist as a real, verifiable, portable artefact. It
does not decide who authorises the content of those ladders.**

That question — who decides which exercise sits above which, and on what evidence — is deliberately
left open and unresolved. It is not answered by:

- inference from a difficulty coefficient, a phase mapping, an exercise name or catalogue order;
- the retired Stage-1 profiles;
- reading a family's current position as if it declared a hierarchy.

It needs its own decision and its own stage. Until then the app has a place to put a ladder and
nothing to put in it, which is the honest state.

## 9. Verification

| Claim | Where it is proved |
| --- | --- |
| A relation round-trips value for value | `ProgressionRelationRepositoryTest.aRelationComesBackAsTheEqualValueFieldForField` |
| Several families coexist and none leaks | `…severalFamiliesCoexistAndOneNeverLeaksIntoAnother` |
| Several variants on one level survive | `…severalVariantsOnOneLevelAreStoredAndReadBackAsSeparateDeclarations` |
| Canonical order is the read's order | `…variantsAreHeldInCanonicalLevelThenExerciseIdOrder` |
| Duplicate exercise refused (domain) | `…oneExerciseCannotBeDeclaredTwiceInOneFamily` |
| Duplicate exercise refused (table) | `…thePrimaryKeyItselfRefusesADuplicateFamilyExercisePair` |
| Non-contiguous levels refused | `…nonContiguousLevelsAreRefused` |
| Full prescription order survives | `…theFullPerSetPrescriptionSurvivesInBothDimensions` |
| Unknown family returns `null` | `…anUnknownFamilyIsAnsweredNullAndNeverAFabricatedRelation` |
| Empty database returns `null` | `…anEmptyCatalogueAnswersNullForEveryFamily` |
| Stored column holds every target | `…theStoredColumnHoldsEveryPerSetTargetAndTheWholeDimensionToken` |
| Migration 17→18 creates the table only | `ProgressionRelationCatalogueIntegrationTest.theStepCreatesTheTableAndWritesNoRowAndTouchesNothingElse` |
| Table absent at 17, present at 18 | `…theTableIsAbsentAtSeventeenAndPresentAtEighteen` |
| Key / no index / no FK / no defaults | `…theStoredTableHasTheDeclaredColumnsKeyAndNoDefaults` |
| Upgraded DB holds no ladder row | `…aFullyUpgradedDatabaseHoldsNoLadderRow` |
| Empty catalogue + real provider → `null` | `…anEmptyCatalogueBackedProviderAnswersNullForEveryFamily` |
| Stored ladder is served by the same provider | `…theSameProviderServesAStoredLadderAsTheStoredValue` |
| A ladder survives deleting every Program | `…aDeclaredLadderSurvivesADeleteOfEveryPlanInTheDatabase` |
| One repository, separate from adaptive history | `ProgressionRelationPersistenceArchitectureTest.thereIsExactlyOneRelationRepositoryAndItIsNotTheAdaptiveRepository` |
| One production provider over that repository | `…thereIsExactlyOneProductionRelationProviderOverTheOneRepository` |
| Provider fabricates nothing | `…theProviderContainsNoDefaultNoInferenceAndNoFallback` |
| No ladder-adjacent source is reached | `…theCataloguePathReadsNoLadderAdjacentSource` |
| Repository invents no domain rule | `…theRepositoryInventsNoRuleTheDomainAlreadyOwns` |
| No level or difficulty heuristic | `…noLayerOfThePersistencePathComputesALevelOrADifficulty` |
| No prescription collapse | `…noLayerCollapsesAPerSetPrescriptionToOneNumber` |
| Existing converter reused | `…thePerSetColumnUsesTheSchemasExistingConverterAndDeclaresNoNewOne` |
| Production still wires the empty source | `…productionStillWiresTheEmptyLadderSourceAndNotThisProvider` |

RED gate: `scripts/program-stage31-red-mutations.sh` — 12 mutations, none repeating P30's rows, each
aimed at the layer that *decides* the rule it breaks.

## 10. Revised, not relaxed

Five pinned assertions were revised by this stage. Each keeps its invariant and states the new fact
explicitly; none was deleted or turned from positive into negative:

| Assertion | Revision |
| --- | --- |
| `everyTargetEntityStandsOnTheOwnershipGraph` | `["app_state"]` → `["app_state", "progression_relation_variant"]`, plus a new positive assertion that the catalogue declares **no** foreign key. A ladder is nobody's child on purpose. |
| `deletingAProgramRemovesEveryRowItOwns…` | `TABLES.size - 1` → `TABLES.size - 2`, plus a new assertion that the ladder catalogue is untouched by the Program delete. Still fails if a third table starts surviving. |
| `theHarnessRegistersTheNineteenTargetTables…` | Renamed to `…TheTwentyTargetTables…`; the closed list gains one table. The rule ("the map is the target schema, not a wider one") is unchanged. |
| `everyDeclaredDaoQueryIsTheOneTheRepositorySuitesExecute` | `ProgramDaoSql.WRITES` gains the per-family ladder delete, with its shape asserted (names its own table, scoped to one family, mentions no plan column). |
| `theTargetEntitySetIsExactlyTheOneTheArchitectureNames`, `AdaptivePersistenceSchemaTest` entity/version/migration lists | Version 17 → 18, the entity list gains `ProgressionRelationVariantEntity`, and the chain assertion moves to `MIGRATION_17_18`. |

## 11. Related documents

- `docs/PROGRAM_ADAPTIVE_FAMILY_CLASSIFICATION.md` — P30, the family-membership gap this stage follows.
- `docs/PROGRAM_ADAPTIVE_INTEGRATION.md` — §30 step 12, which recorded *both* missing facts; P31
  closes the second one and changes nothing in that document's decision semantics.
- `docs/PROGRAM_ADAPTIVE_ENGINE.md` — the `ProgramProgressionRelation` value this stage persists.
- `docs/PROGRAM_LEGACY_REMOVAL.md` — the Stage-1 generation whose profiles stay forbidden.