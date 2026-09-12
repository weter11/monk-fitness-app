package com.monkfitness.app.animation

/**
 * SupportDefinition completely describes how the body is supported in an exercise.
 * Examples:
 * - Standard Push-Up: PivotType.FEET, contacts = LEFT_HAND, RIGHT_HAND, LEFT_TOES, RIGHT_TOES
 * - Knee Push-Up: PivotType.KNEES, contacts = LEFT_HAND, RIGHT_HAND, LEFT_KNEE, RIGHT_KNEE
 * - Decline Push-Up: PivotType.FEET, contacts = LEFT_HAND, RIGHT_HAND, LEFT_TOES, RIGHT_TOES, supportHeight = benchHeight
 * - Plank: PivotType.FEET, contacts = LEFT_FOREARM, RIGHT_FOREARM, LEFT_TOES, RIGHT_TOES
 * - Lunge: PivotType.FEET, contacts = LEFT_FOOT, RIGHT_FOOT
 * - Bridge: PivotType.FEET, contacts = LEFT_FOOT, RIGHT_FOOT, HIPS
 */
data class SupportDefinition(
    val pivot: PivotType,
    val contacts: Set<SupportContact>,
    val supportHeight: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val offsetZ: Float = 0f
) {
    /**
     * **B-5 — the single resolution of the R8 source "Production Metadata support context"**
     * (`RFC_RUNTIME_SKELETON_ARCHITECTURE` §5 R8): the Support Declaration this definition declares,
     * i.e. the support points the pose states its body rests on.
     *
     * R8 fixes the Frame Context's sources — the External Environment Definition plus this
     * declaration — and requires that the resolved context reach the carrier through exactly one
     * SkeletonPipeline-performed injection at frame start. The declaration itself never enters the
     * carrier (R8/R11: Production Metadata bypasses it), so EVERY consumer that resolves the Frame
     * Context must resolve it from here: the builder entry point
     * (`SkeletonPipeline.buildAndInject`) does, and so must the renderer path callers, which hold
     * the pose's `metadata` but not a declaration source on the built pose.
     *
     * This accessor exists so that resolution has exactly ONE definition in production. Before it,
     * each renderer caller re-implemented `contacts.map { it.point }` by hand (and one of them
     * silently did not, publishing an EMPTY support model — the B-5 defect); an omitted or divergent
     * copy is a silent behavior change, not a compile error, because §5 R8 has no reason to forbid a
     * second derivation.
     *
     * Allocation-free for an undeclaring pose (the overwhelmingly common case: no contacts ⇒ the
     * shared empty set).
     */
    val supportPoints: Set<SupportPoint>
        get() = if (contacts.isEmpty()) emptySet() else HashSet<SupportPoint>(contacts.size).apply {
            for (contact in contacts) add(contact.point)
        }
}
