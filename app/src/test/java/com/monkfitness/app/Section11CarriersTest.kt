package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import com.monkfitness.app.validation.poses.*
import org.junit.Assert.*
import org.junit.Test

/**
 * M5 status audit (RFC_GAP_CLOSURE §M5) — pins the *truthful* state of the §1.1 intent carriers.
 *
 * The RFC originally claimed M5 was "automatic once M2 lands". That is false. This suite proves the
 * actual state by runtime observation rather than grep:
 *  - The **live** subset of §1.1 — `contacts`, `contactPrecedence`, `postureIntent` — IS populated
 *    by the validation instruments and consumed by the [ConstraintSolver] (this is what M3/M4 exercise).
 *  - The **dead** subset — `spineIntent`, `jointIntents` — is NEVER written by any production or
 *    validation pose (the authoring helpers write directly to node rotations instead), so they remain
 *    in their empty/identity default after a full build. They have no consumer in the engine yet.
 *  - The **live** subset after B1 — `limbTargets` — IS now populated by every `bakeIkLimb` call (which
 *    forwards its end joint + world target into the carrier) and IS consumed by the engine-owned
 *    `IkStage` (RFC_BRANCH_B_IMPLEMENTATION §2 B1). This is the dead→live flip that defines B1-complete;
 *    `spineIntent`/`jointIntents` remain dead until B2.
 *
 * When the deferred `BasePose`→`IntentBuilder` intent-only migration lands (B2/B3), the remaining
 * dead-carrier asserts below must be flipped to assert they ARE populated — that is M5-complete.
 */
class Section11CarriersTest {

    private val def = SkeletonDefinition.DEFAULT_ADULT

    private fun productionPoses(): List<Pair<String, () -> PoseBuilder>> = listOf(
        "StandardPushUp" to { StandardPushUpPose() },
        "AirSquat" to { AirSquatPose() },
        "AlternatingForwardLunges" to { AlternatingForwardLungesPose() },
        "StaticForearmPlank" to { StaticForearmPlankPose() },
        "HamstringStretch" to { HamstringStretchPose() },
        "StandardPullUp" to { StandardPullUpPose() }
    )

    private fun contactPoses(): List<Pair<String, () -> BaseValidationPose>> = listOf(
        "DeepOverheadSquat" to { DeepOverheadSquatPose() },
        "DeadHang" to { DeadHangPose() },
        "MiddleSplit" to { MiddleSplitPose() },
        "PikeSit" to { PikeSitPose() }
    )

    @Test
    fun liveCarriersPopulatedByContactInstruments() {
        for ((name, factory) in contactPoses()) {
            val pose = factory().build(PoseContext(0.5f, Side.LEFT, def))
            // `contacts` + `postureIntent`/`contactPrecedence` are the live §1.1 carriers the engine
            // (ConstraintSolver) actually consumes. At least one must be non-empty for a contact pose.
            val anyLive = pose.contacts.isNotEmpty() ||
                pose.postureIntent.kind != PostureIntent.Kind.CUSTOM ||
                pose.contactPrecedence.isNotEmpty()
            assertTrue("$name must populate a live §1.1 carrier (contacts/postureIntent/precedence)", anyLive)
            assertTrue("$name must register engine contacts", pose.contacts.isNotEmpty())
        }
    }

    @Test
    fun liveLimbTargetsPopulatedByPoses() {
        // B1 dead→live flip: every pose that bakes a limb now also declares a `limbTargets`
        // entry (bakeIkLimb forwards its end joint + world target into the §1.1 carrier).
        val all = productionPoses() + contactPoses()
        for ((name, factory) in all) {
            val pose = when (factory) {
                in productionPoses().map { it.second } -> (factory() as PoseBuilder).build(PoseContext(0.5f, Side.LEFT, def))
                else -> (factory() as BaseValidationPose).build(PoseContext(0.5f, Side.LEFT, def))
            }
            // The leg/arm IK calls populate the carrier; at least the limbs baked must be present.
            assertTrue("$name must populate limbTargets (B1 carrier live) got=${pose.limbTargets.size}", pose.limbTargets.size > 0)
        }
    }

    @Test
    fun deadCarriersNeverWrittenByPoses() {
        val all = productionPoses() + contactPoses()
        for ((name, factory) in all) {
            val pose = when (factory) {
                in productionPoses().map { it.second } -> (factory() as PoseBuilder).build(PoseContext(0.5f, Side.LEFT, def))
                else -> (factory() as BaseValidationPose).build(PoseContext(0.5f, Side.LEFT, def))
            }
            // The dead §1.1 carriers must stay in their default/empty state. `SpineCurve` structural
            // equality is unreliable here (Vector3 lacks equals), so assert the scalar fields directly.
            assertEquals("$name: spineIntent.lumbarRad must stay 0 (carrier dead)", 0f, pose.spineIntent.lumbarRad, 1e-6f)
            assertEquals("$name: spineIntent.thoracicRad must stay 0 (carrier dead)", 0f, pose.spineIntent.thoracicRad, 1e-6f)
            assertEquals("$name: jointIntents must stay empty (carrier dead) got=${pose.jointIntents.size}", 0, pose.jointIntents.size)
        }
    }

    @Test
    fun limbTargetsConsumedByPipeline() {
        // End-to-end: after a full pipeline produceFrame (IkStage → Solver → Finalizer) the B1
        // `limbTargets` carrier is retained and the dead `spineIntent`/`jointIntents` carriers
        // remain empty (still awaiting B2). When IK_STAGE_ACTIVE is on, IkStage re-bakes every
        // limb from the carrier; the frame must be identical to the off-baseline (see IkStageTest).
        for ((name, factory) in contactPoses()) {
            val out = SkeletonPipeline(def).produceFrame(factory(), PoseContext(0.5f, Side.LEFT, def))
            assertTrue("$name: limbTargets retained through pipeline got=${out.pose.limbTargets.size}", out.pose.limbTargets.size > 0)
            assertEquals("$name: spineIntent.lumbarRad untouched got=${out.pose.spineIntent.lumbarRad}", 0f, out.pose.spineIntent.lumbarRad, 1e-6f)
            assertEquals("$name: spineIntent.thoracicRad untouched got=${out.pose.spineIntent.thoracicRad}", 0f, out.pose.spineIntent.thoracicRad, 1e-6f)
            assertEquals("$name: jointIntents untouched by pipeline got=${out.pose.jointIntents.size}", 0, out.pose.jointIntents.size)
        }
    }
}
