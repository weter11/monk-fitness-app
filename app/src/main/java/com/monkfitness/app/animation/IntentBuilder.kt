package com.monkfitness.app.animation

/**
 * Architecture v2 — Branch B (Declarative Pose Authoring), phase **B0** substrate.
 *
 * `IntentBuilder` is the **single fluent surface a pose uses to populate §1.1 intent** on a
 * [SkeletonPose]. It is the first half of the "Pose expresses intent / Engine owns geometry"
 * boundary (`RFC_DECLARATIVE_AUTHORING.md`): a pose declares *what* the body should do through these
 * methods; engine stages (IkStage / Finalizer / Solver) later derive *how*.
 *
 * B0 scope (per `RFC_BRANCH_B_IMPLEMENTATION.md`):
 * - This class is **additive** — it does not change any existing carrier, helper, or behavior.
 * - Every declaration below writes the **same** §1.1 field the legacy direct assignment wrote
 *   (e.g. `spine(lumbar, thoracic)` sets `pose.spineIntent`), so migrated and pre-migration poses
 *   produce identical intent. No geometry is computed here (that stays in the engine).
 * - `BasePose` now routes its intent authors (`buildGaze`, `declarePosture`, `overrideExtremityOrientation`,
 *   and the `contacts` add inside `bakeIkLimb`) through an `IntentBuilder`, so **no `BasePose` helper
 *   assigns a §1.1 carrier field directly** — proving the builder is the only intent mutator within the
 *   pose layer. The hard visibility lockdown (making the carriers non-public) is deferred to phase B4,
 *   the irreversible purge step, to keep the build green (compile-first policy).
 *
 * This is NOT an implementation of the consumer stages — only the authoring surface they will read.
 */
class IntentBuilder(private val pose: SkeletonPose) {

    /** Declares a two-segment spine curve (lumbar + thoracic, shared axis). Replaces direct
     *  `pose.spineIntent = SpineCurve(...)`. Consumed by the Finalizer (phase B2). */
    fun spine(lumbarRad: Float, thoracicRad: Float, axis: Vector3 = Vector3(1f, 0f, 0f)): IntentBuilder {
        pose.spineIntent = SpineCurve(lumbarRad, thoracicRad, axis)
        return this
    }

    /** Declares a relative articulation of a single joint (chest/hip/girdle/ankle/wrist). Replaces
     *  direct `pose.jointIntents.add(...)`. Consumed by the Finalizer (phase B2). */
    fun joint(joint: Joint, rotation: JointRotation): IntentBuilder {
        pose.jointIntents.add(RelativeArticulation(joint, rotation))
        return this
    }

    /** Declares a world-space target for a limb end-effector / intermediate joint. Replaces the
     *  intent half of `bakeIkLimb` (the geometry solve stays in the engine, phase B1). */
    fun limbTarget(joint: Joint, world: Vector3): IntentBuilder {
        pose.limbTargets.add(WorldTarget(joint, world.copy()))
        return this
    }

    /** Declares the coarse posture intent (F2) the Solver should honour. Replaces
     *  `pose.postureIntent = PostureIntent(...)`. Consumed by the Solver (M3 / phase B3). */
    fun posture(kind: PostureIntent.Kind, tolerance: Float = 0f): IntentBuilder {
        pose.postureIntent = PostureIntent(kind, tolerance)
        return this
    }

    /** Declares a fixed support contact (PR-04) so the Solver can settle the root. Replaces the
     *  `pose.contacts.add(ContactSpec(...))` inside `bakeIkLimb`. */
    fun contact(spec: ContactSpec): IntentBuilder {
        pose.contacts.add(spec)
        return this
    }

    /** Declares contact-conflict precedence order (F7). Earlier entries win. Replaces
     *  `pose.contactPrecedence.clear()` + `add`. */
    fun precedence(joints: List<Joint>): IntentBuilder {
        pose.contactPrecedence.clear()
        for (j in joints) pose.contactPrecedence.add(j.name)
        return this
    }

    /** Declares the gaze-as-target (Phase 7). Replaces `pose.headTarget = HeadTarget(...)`. */
    fun headTarget(world: Vector3, upBias: Vector3 = Vector3(0f, 1f, 0f)): IntentBuilder {
        pose.headTarget = HeadTarget(world.copy(), upBias.copy())
        return this
    }

    /** Declares an extremity whose geometry the engine must preserve verbatim (W1). Replaces
     *  `pose.overrideExtremityOrientation(extremity)`. The Finalizer reads this (dormant today). */
    fun overrideExtremity(extremity: Extremity): IntentBuilder {
        pose.overrideExtremityOrientation(extremity)
        return this
    }

    /** Declares the motion driver hint (§1.1). */
    fun motion(driver: Any?): IntentBuilder {
        pose.motion = driver
        return this
    }

    /** Declares the camera framing hint (§1.1). */
    fun camera(hint: Any?): IntentBuilder {
        pose.camera = hint
        return this
    }

    /** Declares the environment hint (§1.1). */
    fun environment(hint: Any?): IntentBuilder {
        pose.environment = hint
        return this
    }

    /** Exposes the underlying pose once declaration is complete (the pose layer owns the buffer). */
    fun build(): SkeletonPose = pose
}
