# Pose Audit & Fix Tracker

> Running log of poses audited under the two-axis playbook
> (`docs/POSE_AUDIT_AND_FIX_PLAYBOOK.md`). One row per pose family/file.
> Status: `AUDITED` (reviewed, no fix needed) · `FIXED` (defect found + corrected)
> · `KNOWN-LIMITATION` (defect documented, fix out of per-pose scope).

## Push-Up family — FULL REWRITE

All seven files were rewritten from scratch for the best natural-looking
result (rule: minimal-rewrite disabled for poses). Shared conventions
applied across the family:

- **Planted feet** via `buildAnkleArticulation(FOOT_*, plantarFlexion, 0, ankle)`.
- **Scapular protraction** via `buildClavicularRotation` (±0.12f) for a loaded look.
- **Gaze** forward-and-down (`-0.15f` Y) — cervical spine in line with the plank.
- **Wrists** mirrored `HAND→WRIST` at finalize (renderer consumes `WRIST_*`;
  the established plank-family convention, consistent with ~30 other poses).
- **IkStage-safe**: arm IK roots read from the *actual* shoulder node world
  position (after scapular protraction + FK), exactly as the engine-owned
  `IkStage` does → `productionPosesByteIdenticalStageOnVsOff` stays at
  maxDev 0.0.
- **Rigid kinematic plank** — stays engine-**contact-less** (per playbook §2);
  metadata `SupportDefinition.contacts` is renderer-only.

| Pose | File | Layer-1 | Notes |
|---|---|---|---|
| StandardPushUpPose | `poses/BasePushUpPose.kt` (base `build`) | FIXED | Shared prone-plank engine; wrist + IkStage fixes baked in |
| StandardPushUpPose (contacts) | `poses/BasePushUpPose.kt` | REVERTED | Rigid plank must stay contact-less; registering `ContactSpec`s fired ConstraintSolver + `IllegalArgumentException` (20 fails) |
| WidePushUpPose | `poses/WidePushUpPose.kt` | AUDITED | Wide grip (1.9f) + flared elbow pole |
| MilitaryPushUpPose | `poses/MilitaryPushUpPose.kt` | AUDITED | Tight grip (1.0f) + forward hand anchor |
| DiamondPushUpPose | `poses/DiamondPushUpPose.kt` | AUDITED | Narrow grip (~0.1) + in-hugging pole |
| DeclinePushUpPose | `poses/DeclinePushUpPose.kt` | AUDITED | Feet-elevated box prop; `supportHeight` → `PushUpGeometrySolver` |
| KneePushUpPose | `poses/KneePushUpPose.kt` | AUDITED | `PivotType.KNEES` knee-pivot branch |
| PikePushUpPose | `poses/PikePushUpPose.kt` | FIXED | Distinct V-shape `build()`; wrist + IkStage fixes baked in |

## Cross-cutting notes

- **Wrist bypass** (`WRIST=HAND`) is a repo-wide legacy convention (~30
  pose files). Aligning the push-up family to the renderer convention is
  in-scope; a repo-wide migration is a separate Branch-C-scale effort, **out of
  scope** for per-pose auditing.
- **Rigid kinematic planks must stay contact-less.** Registering engine
  `ContactSpec`s for them fires the ConstraintSolver relaxation and regresses the
  suite. Metadata `SupportDefinition.contacts` is the correct renderer-only
  declaration for these poses.

## Legend

- Layer-1 = MonkEngine Integration audit (PASS/FAIL).
- Layer-2 (biomechanics 0–10 scoring) is recorded per-pose in the audit
  conversation, not here.
