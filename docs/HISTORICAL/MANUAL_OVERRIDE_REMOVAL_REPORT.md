# MANUAL_OVERRIDE Removal — Production Poses

**Goal:** the engine is fully responsible for extremity orientation. All temporary
`MANUAL_OVERRIDE` usage was removed from the production pose library. Poses now express
only anatomical intent (limb targets, pelvis/chest lean, grip/wrist articulation where the
exercise genuinely intends it); the engine derives heel/toe and palm/knuckles/fingertips.

No new ankle/wrist rotations were added and no heel/toe or palm/fingertip positions were
hand-authored. Where removing the override leaves a pose visually worse, it was left that
way on purpose — exposing the engine limitation rather than hiding it.

## Overrides removed

| Pose | Extremity(ies) | What was removed |
| --- | --- | --- |
| `KettlebellSwingPose` | FOOT_F, FOOT_B | `ankle.localRotation = leanAngle` counter-rotation + hand-authored heel/toe + `overrideExtremityOrientation`. |
| `LatStretchPose` | FOOT_F, FOOT_B | same as KettlebellSwing (leaning-shank flat foot). |
| `ReverseSnowAngelPose` | FOOT_F, FOOT_B | same (leaning-shank flat foot). |
| `MountainClimberPose` | FOOT_F, FOOT_B | same (leaning-shank flat foot). |
| `SumoSquatPose` | FOOT_F, FOOT_B | `ankle.localRotation = leanAngle` + 45° toe-flare heel/toe authoring + `overrideExtremityOrientation`. `leftToeDir`/`rightToeDir` scratch vars removed. |
| `BaseHipFlexorPose` (`applyBackFoot`) | FOOT_B | back-foot direction authoring (heel/toe + `ankle = leanAngle`) + `overrideExtremityOrientation` in `finalizeHipFlexorPose`. `applyBackFoot` is now a no-op; `worldFootDir`/`localFoot` scratch vars removed. |
| `StaticForearmPlankPose` | FOOT_F, FOOT_B | plantar-flex ankle rotation + heel/toe authoring + `overrideExtremityOrientation`. `invTorsoZ`/`plantar` scratch vars removed. |
| `IsometricSidePlankPose` | FOOT_F, FOOT_B, HAND_A, HAND_P | side-rolled flat-foot + flat-forearm authoring (ankle/hand rotations, heel/toe, palm/knuckles/fingertips) + all four `overrideExtremityOrientation` calls. `invTorsoZ` removed. |
| `BasePushUpPose` | FOOT_F, FOOT_B, HAND_A, HAND_P | knee-pivot and feet-pivot flat-foot authoring (ankle rotations + heel/toe), flat-palm authoring (hand rotations + palm/knuckles/fingertips), and all four `overrideExtremityOrientation` calls. `handPalmOffset`/`handFingertipOffset` removed; `handDirA`/`handDirP` retained as overridable intent fields (now unused by authoring). |

`overrideExtremityOrientation` itself remains defined on `BasePose`/`BaseValidationPose`
(engine-owned API), but is no longer called by any production pose.

## Poses expected to regress visually

These regressions are the *intended* exposure of engine limitations — not bugs to fix:

- **KettlebellSwing / LatStretch / ReverseSnowAngel / MountainClimber** — the planted foot on
  the forward-leaning shank was the whole reason for the override; the engine's
  perpendicular-to-shank derivation will not lay the foot flat, so it may tilt/penetrate.
- **SumoSquat** — loses the intentional 45° sumo toe flare; feet align with the shank.
- **StaticForearmPlank** — loses the plantar-flexed (heels-lifted) foot; foot derives flat.
- **IsometricSidePlank** — the side-rolled frame's stacked/planted feet and flat forearm are
  no longer hand-authored; the engine derivation cannot reproduce the side-rolled orientation.
- **BasePushUp (and subclasses: Standard/Wide/Decline/Diamond/Military/Knee)** — loses the
  precisely tuned plank foot and flat planted palm; the engine derivation replaces them.
- **HalfKneelingStretch / CouchStretch** (via `BaseHipFlexorPose`) — the back foot no longer
  points up-the-wall / flat-backward; it is engine-derived from the shank.

## No fixes yet

No compensating code, no new rotations, no hand-authored endpoints were added. Regressions
are left as-is per instructions. No attempt was made to make the engine derivation match the
old authored geometry.

## Verification

No JVM is available in this environment, so the project could not be compiled or run; visual
regressions above are *expected* from the prior override rationale, not confirmed by rendering.
Code review confirms: no `overrideExtremityOrientation` calls remain in production poses, no
manual heel/toe/palm/fingertips authoring remains, and no new ankle/wrist rotations were added.
