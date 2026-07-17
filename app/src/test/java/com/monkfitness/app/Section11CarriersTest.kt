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
 *  - The **dead** subset — `spineIntent`, `jointIntents` — is NEVER written by any
 *    production or validation pose (the authoring helpers write directly to node rotations instead), so
 *    they remain in their empty/identity default after a full build. They have no consumer in the engine.
 *  - `limbTargets` is **now LIVE** (Branch B B1): every limb-bearing pose declares it via
 *    the `bakeIkLimb` → `WorldTarget` forward, and the pipeline-owned `IkStage` consumes it.
 *    So the "dead → live" flip for `limbTargets` is asserted below.
 *
 * `spineIntent` / `jointIntents` stay dead until B2 (Finalizer consumers). Their asserts remain
 * "must stay empty" until that phase lands.
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
    fun deadCarriersNeverWrittenByPoses() {
        val all = productionPoses() + contactPoses()
        for ((name, factory) in all) {
            val pose = when (factory) {
                in productionPoses().map { it.second } -> (factory() as PoseBuilder).build(PoseContext(0.5f, Side.LEFT, def))
                else -> (factory() as BaseValidationPose).build(PoseContext(0.5f, Side.LEFT, def))
            }
            // `spineIntent` + `jointIntents` remain the dead §1.1 carriers (no B2 consumer yet).
            // `SpineCurve` structural equality is unreliable here (Vector3 lacks equals), so assert
            // the scalar fields directly.
            assertEquals("$name: spineIntent.lumbarRad must stay 0 (carrier dead)", 0f, pose.spineIntent.lumbarRad, 1e-6f)
            assertEquals("$name: spineIntent.thoracicRad must stay 0 (carrier dead)", 0f, pose.spineIntent.thoracicRad, 1e-6f)
            assertEquals("$name: jointIntents must stay empty (carrier dead) got=${pose.jointIntents.size}", 0, pose.jointIntents.size)
        }
    }

    @Test
    fun limbTargetsNowLiveForContactInstruments() {
        // Branch B B1 — `limbTargets` flips dead → live: every limb-bearing validation
        // instrument declares its limbs via the `bakeIkLimb` → `WorldTarget` forward, and the
        // pipeline-owned `IkStage` consumes them.
        for ((name, factory) in contactPoses()) {
            val pose = factory().build(PoseContext(0.5f, Side.LEFT, def))
            assertTrue("$name must populate limbTargets (carrier now live in B1) got=${pose.limbTargets.size}", pose.limbTargets.size > 0)
        }
    }

    @Test
    fun deadCarriersHaveNoEngineConsumer() {
        // End-to-end: after a full pipeline produceFrame (IkStage → Solver → Finalizer),
        // the still-dead carriers (`spineIntent`/`jointIntents`) remain empty, and the now-live
        // `limbTargets` is *consumed* (read) but never *appended to* by the engine (the IkStage
        // is a reader, not a writer of the carrier list). If a future migration starts writing
        // them, this test fails and signals B2/B3 progress.
        for ((name, factory) in contactPoses()) {
            val built = factory().build(PoseContext(0.5f, Side.LEFT, def))
            val before = built.limbTargets.size
            assertTrue("$name: limbTargets must be live before pipeline got=${before}", before > 0)
            val out = SkeletonPipeline(def).produceFrame(factory(), PoseContext(0.5f, Side.LEFT, def))
            assertEquals("$name: spineIntent.lumbarRad untouched got=${out.pose.spineIntent.lumbarRad}", 0f, out.pose.spineIntent.lumbarRad, 1e-6f)
            assertEquals("$name: spineIntent.thoracicRad untouched by pipeline got=${out.pose.spineIntent.thoracicRad}", 0f, out.pose.spineIntent.thoracicRad, 1e-6f)
            assertEquals("$name: jointIntents untouched by pipeline got=${out.pose.jointIntents.size}", 0, out.pose.jointIntents.size)
            assertEquals("$name: limbTargets size must be unchanged by pipeline (read-only) before=$before got=${out.pose.limbTargets.size}", before, out.pose.limbTargets.size)
        }
    }
}
