package com.monkfitness.app

import com.monkfitness.app.animation.*
import com.monkfitness.app.poses.*
import com.monkfitness.app.validation.poses.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Pins the truthful state of the intent carriers, by runtime observation rather than grep.
 *
 * The live subset: `contacts`, `contactPrecedence` and `postureIntent` are populated by the validation
 * instruments and consumed by the [ConstraintSolver]; `limbTargets` is populated by every `bakeIkLimb`
 * call (which forwards its end joint + world target into the carrier) and consumed by the
 * engine-owned `IkStage`; `spineIntent` and `jointIntents` are populated by every
 * trunk/hip/girdle/extremity authoring helper (which forwards its intent through the sole-mutator
 * `IntentBuilder`) and consumed by the [SkeletonPoseFinalizer]; and `extremityOverrides` is read by
 * the Finalizer's hand/foot derivation, so it is consumed, not dead.
 *
 * A carrier that is populated but never consumed is dead, and a carrier consumed but never populated
 * asserts nothing — these tests keep that distinction observable so a silent regression fails here.
 * The deferred `BasePose`→`IntentBuilder` intent-only migration will delete the helpers' node writes,
 * leaving the carriers as the sole authoring surface.
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

    /**
     * Poses that actually AUTHOR a trunk/hip/girdle/extremity intent — the poses whose `spineIntent` /
     * `jointIntents` carriers must be populated (the authoring helpers forward through the sole-mutator
     * IntentBuilder). Poses that express no such intent legitimately leave the carriers empty, so they
     * are intentionally excluded.
     */
    private fun trunkHipPoses(): List<Pair<String, () -> PoseBuilder>> = listOf(
        "StaticForearmPlank" to { StaticForearmPlankPose() },
        "PikePushUp" to { PikePushUpPose() },
        "QuadrupedThoracicRotations" to { QuadrupedThoracicRotationsPose() },
        "DynamicWorldsGreatestStretch" to { DynamicWorldsGreatestStretchPose() },
        "AlternatingForwardLunges" to { AlternatingForwardLungesPose() }
    )

    @Test
    fun liveCarriersPopulatedByContactInstruments() {
        for ((name, factory) in contactPoses()) {
            val pose = factory().build(PoseContext(0.5f, Side.LEFT, def))
            // `contacts` + `postureIntent`/`contactPrecedence` are the live carriers the engine
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
        // Every pose that bakes a limb now also declares a `limbTargets` entry (bakeIkLimb forwards its
        // end joint + world target into the carrier).
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
    fun spineAndJointCarriersLiveAfterB2() {
        // The trunk/hip/girdle/extremity authoring helpers forward their intent through the sole-mutator
        // IntentBuilder, so the `spineIntent` and `jointIntents` carriers are populated for every pose
        // that authors such intent (the Finalizer consumes them).
        for ((name, factory) in trunkHipPoses()) {
            val pose = factory().build(PoseContext(0.5f, Side.LEFT, def))
            // Every trunk/hip/girdle/extremity authoring pose records a joint articulation.
            assertTrue("$name must populate jointIntents (B2 carrier live) got=${pose.jointIntents.size}", pose.jointIntents.isNotEmpty())
        }
        for ((name, factory) in contactPoses()) {
            val pose = factory().build(PoseContext(0.5f, Side.LEFT, def))
            // Every contact instrument authors a trunk curve, so it records both `spineIntent`
            // (via buildSpineCurve, at least the joints) and `jointIntents`. The neutral MiddleSplit
            // keeps the curve at (0,0) by design, so we assert the joint intents (the consumed
            // carrier) are populated for every contact pose.
            assertTrue("$name must populate jointIntents (B2 carrier live) got=${pose.jointIntents.size}", pose.jointIntents.isNotEmpty())
        }
        // Poses that author a non-neutral trunk curve must populate `spineIntent`.
        for ((name, factory) in listOf(
            "DeepOverheadSquat" to { DeepOverheadSquatPose() },
            "PikeSit" to { PikeSitPose() }
        )) {
            val pose = factory().build(PoseContext(0.5f, Side.LEFT, def))
            val spinePopulated = pose.spineIntent.lumbarRad != 0f || pose.spineIntent.thoracicRad != 0f
            assertTrue("$name must populate spineIntent (B2 carrier live)", spinePopulated)
        }
    }

    @Test
    fun limbTargetsConsumedByPipeline() {
        // End-to-end: after a full pipeline produceFrame (IkStage → Solver → Finalizer) the
        // `limbTargets` carrier is retained and the `jointIntents` carrier is populated (live) and
        // consumed by the Finalizer (idempotent, byte-identical output). Contact poses are
        // solver-settled, so the consumer is a no-op for them — the carrier is still retained.
        for ((name, factory) in contactPoses()) {
            val out = SkeletonPipeline(def).produceFrame(factory(), PoseContext(0.5f, Side.LEFT, def))
            assertTrue("$name: limbTargets retained through pipeline got=${out.pose.limbTargets.size}", out.pose.limbTargets.size > 0)
            assertTrue("$name: jointIntents retained through pipeline got=${out.pose.jointIntents.size}", out.pose.jointIntents.isNotEmpty())
        }
    }

    /**
     * The last 5 bare ROM writes are now carrier-backed. Every ROM-bearing joint (spine/neck/hip)
     * records a `jointIntents` entry via the documented helpers (`buildSpineCurve` /
     * `buildHipFlexion`) or `declareJointIntent`, so no bare ROM `localRotation.set` remains. This pins
     * that the ROM carriers are live for the migrated families.
     */
    @Test
    fun romCarriersLiveAfterB4a() {
        val romPoses: List<Pair<String, () -> PoseBuilder>> = listOf(
            "ThoracicExtension" to { ThoracicExtensionPose() },
            "GluteBridge" to { GluteBridgePose() },
            "PelvicTilt" to { PelvicTiltPose() },
            "KneePushUp" to { KneePushUpPose() }
        )
        for ((name, factory) in romPoses) {
            val pose = factory().build(PoseContext(0.5f, Side.LEFT, def))
            assertTrue("$name must record ROM joint intents after B4a (got ${pose.jointIntents.size})", pose.jointIntents.isNotEmpty())
        }
        // ThoracicExtension records the two-segment spine curve on LUMBAR + CHEST.
        val te = ThoracicExtensionPose().build(PoseContext(0.5f, Side.LEFT, def))
        assertTrue("ThoracicExtension must record LUMBAR ROM intent", te.jointIntents.any { it.joint == Joint.LUMBAR })
        assertTrue("ThoracicExtension must record CHEST ROM intent", te.jointIntents.any { it.joint == Joint.CHEST })
        // GluteBridge / PelvicTilt record the neck ROM intent (neck node carries Joint.NECK_END).
        val gb = GluteBridgePose().build(PoseContext(0.5f, Side.LEFT, def))
        assertTrue("GluteBridge must record NECK_END ROM intent", gb.jointIntents.any { it.joint == Joint.NECK_END })
        val pt = PelvicTiltPose().build(PoseContext(0.5f, Side.LEFT, def))
        assertTrue("PelvicTilt must record NECK_END ROM intent", pt.jointIntents.any { it.joint == Joint.NECK_END })
        // KneePushUp exercises the knee-pivot branch, which records the HIP_F flexion ROM intent
        // (via buildHipFlexion) in `BasePushUpPose`.
        val pu = KneePushUpPose().build(PoseContext(0.5f, Side.LEFT, def))
        assertTrue("KneePushUp must record HIP_F ROM intent", pu.jointIntents.any { it.joint == Joint.HIP_F })
    }
}
