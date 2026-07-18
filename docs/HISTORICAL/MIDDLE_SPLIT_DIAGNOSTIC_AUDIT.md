# Middle Split — Audit under the Diagnostic-Instrument Rule

> **New governing rule (this audit):**
> **Validation poses are no longer development targets. They are diagnostic instruments.**
>
> A development *target* is something the MonkEngine runtime is dragged toward until it goes green.
> A diagnostic *instrument* is something you point at the MonkEngine runtime to read its true state —
> its reading must stay honest whether the MonkEngine runtime passes or fails. You never adjust the
> instrument to change the reading; that would be tampering with the thermometer to lower
> the fever.

---

## 1. Why Middle Split needed re-auditing

Middle Split is the one validation pose whose history contains a **direct contradiction**,
and it is the clearest test of the new rule.

Two verdicts co-exist in the repo, reached under the *old* contract
(`VALIDATION.md §2`: "the MonkEngine runtime must satisfy validation; validation never bends to the
engine"):

1. **Middle Split is a deliberate broken reference** (`VALIDATION.md §11.1`,
   `ENGINE_INVESTIGATION_REPORT` UNI-2). Its foot targets sat *inside* the thigh bone, so
   `straight = true` could not be honoured; the MonkEngine runtime fell back to a bent limb; a test
   (`middleSplitDetectableUnderEngineeringValidation`) **asserted the pose stays bent** so
   the MonkEngine runtime defect stayed visible. In this reading the pose is doing its job precisely by
   *failing* — it is an instrument.

2. **Middle Split is a reference error that must be fixed in the pose**
   (`PELVIC_HIP_COMPLEX_INVESTIGATION §P6`, `ENGINEERING_VALIDATION_AUDIT §1`). Because a
   straight-leg split geometrically requires feet at ≈full leg reach, the `79.2` spread was
   called "anatomically wrong" and the pose was retargeted to be reachable. In this reading
   the pose is a *target* that was mis-authored and then corrected to green.

Verdict (2) won in the code: `MiddleSplitPose.kt` was rewritten and the test renamed
`middleSplitIsNowAValidStraightReference`, which now asserts the limbs resolve **straight**.

Under the **old** rule both readings were arguable. Under the **new** rule they are not:
verdict (2) is exactly the prohibited move.

---

## 2. What was actually changed to make Middle Split green

Comparing the authoring described in `ENGINEERING_VALIDATION_AUDIT §1` (the "broken
reference") against the current `MiddleSplitPose.kt`:

| Aspect | Diagnostic (original) | Retargeted (current) | Effect |
| --- | --- | --- | --- |
| Foot target spread | `hipWidth * 3.6 = 79.2` | `hipWidth + thigh + shin = 232` | hip→foot moved from **58.9** (inside L1=112) to **full reach 210** |
| Pelvis height | `y = 14` | `y = 0` | drops the seat to the floor so feet land at y=0 |
| Arm hand target | `shoulderW * … = 79.2` | `shoulderW + upperArm + forearm = 192` | arm reach moved from **33.2** (inside L1=80) to **full reach 146** |
| Hip external rotation | none | `buildHipRotation(0.8, ∓1)` | knees face up |
| Poles | forward+lateral | pure lateral | knee tracks foot |

The decisive change is the target distances. At `58.9 < 112` the MonkEngine's
`solveStraightLimb` takes the `dist < L1` branch (`SkeletonMath.kt:660`) and returns a
**bent** triangle limb — the straight intent is silently dropped. That is the reading the
instrument exists to produce. Moving the targets to full reach (`210` / `146`) puts them in
`[L1, L1+L2]`, so the same function returns a genuinely straight limb. **the MonkEngine runtime was not
changed; the instrument was moved off the fault so the fault stopped registering.**

This is green-tuning: the pose was adjusted until `STRAIGHT_LIMB_INTENT` reported valid,
not because the MonkEngine runtime learned to honour a straight limb at an in-proximal-radius target,
but because the pose stopped asking it to.

---

## 3. Findings under the diagnostic-instrument rule

### F1 — The retarget is instrument tampering (Critical)
Widening the spread from `79.2` to `232` changed the *reading* (bent → straight) without
changing the *engine* being measured. A diagnostic instrument must not be moved to produce
a passing reading. This is the core violation of the new rule.

### F2 — the MonkEngine runtime limitation it used to measure still exists (High)
`solveStraightLimb` still silently drops `straight = true` when `dist < L1` (the UNI-9
fallback). Nothing in the MonkEngine runtime changed. By retargeting, the project lost its
*named reference case* for that limitation. The `BrokenStraightLimbPose` fixture inside
`ValidatorRomClusterTest` was introduced as a stand-in, which is good coverage — but it is a
private test fixture, not a first-class, viewer-inspectable diagnostic pose, so the MonkEngine runtime
gap is no longer visible in the Engineering Validation surface a human opens.

### F3 — Two docs now contradict the code (High)
`VALIDATION.md §11.1` still says Middle Split is kept broken *on purpose* ("None (keep
as-is)", "Fixing the pose … would hide the bug the test exists to catch"), while the code
and `ENGINEERING_VALIDATION_AUDIT §1` say it was fixed. Whichever philosophy governs, the
docs must agree with the code. Under the new rule, `§11.1`'s original intent is the correct
one and the code drifted away from it.

### F4 — "Anatomically wrong reference" was a category error (Medium)
`§P6` justified the retarget by calling the `79.2` spread "anatomically wrong". But the
instrument was never claiming *"a human sits with feet 79 units apart"*; it was claiming
*"a straight limb was requested at a target the MonkEngine runtime cannot straighten, show me what the
engine does"*. Judging a diagnostic probe by anatomical realism is the wrong axis — a probe
is judged by whether its reading is faithful, not whether the configuration is a pose a
gymnast would hold. Under the new rule this distinction is explicit.

### F5 — The renamed test bakes the tampering into the guardrails (High)
`middleSplitIsNowAValidStraightReference` now *asserts the limbs resolve straight*. That
locks the tampered instrument in place: any future attempt to restore the honest probe
would "break" this test, so the guardrail now defends the wrong behaviour.

---

## 4. Recommendation

Restore Middle Split to an **honest diagnostic instrument** and make every doc/test agree:

1. **Pose (`MiddleSplitPose.kt`):** put the straight-limb targets back inside the proximal
   bone (the original `79.2`-class spread) so the pose once again *requests* a straight
   split the MonkEngine runtime cannot currently deliver, and reads out the bent fallback. Keep the pose
   honestly labelled as a straight-intent probe in its KDoc. Do **not** re-tune it to green.
2. **Test (`ValidatorRomClusterTest`):** the Middle Split assertion must reflect the
   instrument's true reading — the straight intent is **detected as dropped** (invalid), the
   engine limitation is surfaced — rather than asserting a green straight limb. Keep
   `BrokenStraightLimbPose` too; it is complementary coverage, but Middle Split itself is
   the named, viewer-visible instrument.
3. **Docs:** rewrite `VALIDATION.md §2`/§8/§9 so the responsibility direction is expressed
   as *"the instrument reports the MonkEngine's true state; you fix the MonkEngine runtime or you record the
   reading, you never retune the instrument to green"*, and reconcile `§11.1` with the code
   (it returns to "keep as the straight-intent reference"). Flag
   `ENGINEERING_VALIDATION_AUDIT §1` and `PELVIC_HIP §P6` as **superseded** — their "fix the
   pose" verdicts were made under the old target model.

This is the change applied in this session (see `§5`).

---

## 5. Applied changes (this session)

- `MiddleSplitPose.kt` — reverted the green-tuning: straight-limb targets returned to
  in-proximal-radius (spread back to the `hipWidth`-scaled reference), pelvis restored, KDoc
  rewritten to state the pose is a **straight-intent diagnostic**, not a satisfiable target.
- `ValidatorRomClusterTest.middleSplitIsNowAValidStraightReference` →
  `middleSplitSurfacesDroppedStraightIntent`: asserts the straight intent is flagged
  (instrument reads the fault), hip ROM stays in range.
- `docs/VALIDATION.md` — §2/§8/§9 reframed to the diagnostic-instrument rule; §11.1
  reconciled; supersession notes added.
- `AGENTS.md` — memory anchor updated with the new governing rule.
