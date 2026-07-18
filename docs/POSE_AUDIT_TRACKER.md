# Pose Audit & Fix Tracker

> Running log of poses audited under the two-axis playbook
> (`docs/POSE_AUDIT_AND_FIX_PLAYBOOK.md`). One row per pose family/file.
> Status: `AUDITED` (reviewed, no fix needed) · `FIXED` (defect found + corrected)
> · `KNOWN-LIMITATION` (defect documented, fix out of per-pose scope).

## Push-Up family

| Pose | File | Layer-1 | Defect found | Fix | PR |
|---|---|---|---|---|---|
| StandardPushUpPose | `poses/BasePushUpPose.kt` (base `build`) | FAIL→FIXED | `WRIST_A.set(HAND_A)` discarded engine wrist articulation | `buildWristArticulation(HAND_A/HAND_P, 0.35f, 0f, hand)` | #177 |
| StandardPushUpPose (contacts) | `poses/BasePushUpPose.kt` | FAIL→REVERTED | Declared 4 contacts unregistered → registered engine `ContactSpec`s → fired ConstraintSolver on a rigid plank, `IllegalArgumentException` (20 test failures) | Reverted: rigid planks stay contact-less; metadata contacts are renderer-only (playbook §2) | #177 |
| WidePushUpPose | `poses/WidePushUpPose.kt` | AUDITED | None — only overrides grip/poles/metadata; inherits fixed base | — | — |
| MilitaryPushUpPose | `poses/MilitaryPushUpPose.kt` | AUDITED | None — inherits fixed base | — | — |
| DiamondPushUpPose | `poses/DiamondPushUpPose.kt` | AUDITED | None — inherits fixed base | — | — |
| DeclinePushUpPose | `poses/DeclinePushUpPose.kt` | AUDITED | None — `supportHeight=40` box prop consumed by `PushUpGeometrySolver`; inherits fixed base | — | — |
| KneePushUpPose | `poses/KneePushUpPose.kt` | AUDITED | None — `PivotType.KNEES`; knee contacts are declarative metadata (rigid-plank → contact-less) | — | — |
| PikePushUpPose | `poses/PikePushUpPose.kt` | FAIL→FIXED | Overrides `build()` so missed base wrist fix: `WRIST_A.set(HAND_A)` / `WRIST_P.set(HAND_P)` copy | `buildWristArticulation(HAND_A/HAND_P, -torsoGlobalPitch, 0f, hand)` | (this PR) |

## Cross-cutting notes

- **Wrist bypass is a repo-wide legacy convention** (`WRIST_X.set(HAND_X)` appears in ~30 pose files). Fixing it in the two push-up code paths (`BasePushUpPose.build` + `PikePushUpPose.build`) aligns the family; a repo-wide migration is a separate Branch-C-scale effort, **out of scope** for per-pose auditing.
- **Rigid kinematic planks must stay contact-less.** Registering engine `ContactSpec`s for them fires the ConstraintSolver relaxation and regresses the suite. Metadata `SupportDefinition.contacts` is the correct renderer-only declaration for these poses.

## Legend

- Layer-1 = MonkEngine Integration audit (PASS/FAIL).
- Layer-2 (biomechanics 0–10 scoring) is recorded per-pose in the audit conversation, not here.
