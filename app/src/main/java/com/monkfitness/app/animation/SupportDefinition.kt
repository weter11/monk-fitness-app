package com.monkfitness.app.animation

/**
 * How the body is supported in an exercise: the pivot plus the contact points that rest on a
 * surface (e.g. a push-up pivots on FEET with both hands and both toes in contact; a plank plants
 * both forearms instead of the hands).
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
     * The support points this pose states its body rests on — the Support Declaration, which never
     * enters the carrier itself (Production Metadata bypasses it). Every consumer that resolves the
     * Frame Context must resolve it from here: the builder entry point does, and so must renderer
     * callers, which hold the pose's `metadata` but not a declaration source on the built pose.
     *
     * This accessor exists so that resolution has exactly ONE definition in production: a
     * hand-rolled `contacts.map { it.point }` that is omitted or diverges is a silent behaviour
     * change, not a compile error, and one caller silently published an EMPTY support model.
     *
     * Allocation-free for an undeclaring pose (no contacts ⇒ the shared empty set).
     */
    val supportPoints: Set<SupportPoint>
        get() = if (contacts.isEmpty()) emptySet() else HashSet<SupportPoint>(contacts.size).apply {
            for (contact in contacts) add(contact.point)
        }
}
