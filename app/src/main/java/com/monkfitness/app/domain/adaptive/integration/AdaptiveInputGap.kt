package com.monkfitness.app.domain.adaptive.integration

/**
 * Why an adaptive pass produced no decision at all — an **expected** result, named (§20, §28).
 *
 * Each member is one concrete missing or ineligible fact, and the vocabulary exists because the
 * alternative is the one thing §28 forbids: representing materially different outcomes with `null`,
 * an empty list or `false`. *"The next opportunity does not present a family you just trained"* and
 * *"the program is manual, so nothing adapts it"* are different answers to different questions, and a
 * caller — a debug view, a test, the owner reading the audit — must be able to tell them apart.
 *
 * ### What a gap is not
 *
 * A gap is **not** a decision. Nothing adaptive is written for one: no decision row, no adjustment and
 * no family-state change, because no window was evaluated (`AdaptiveCompletion.NothingDecided`). The
 * engine was never asked, so there is nothing to record — and a fabricated `NOT_APPLIED` row would
 * claim a decision nobody took (§16, §18).
 *
 * A gap is also not a failure. `INVALID_DATA` (stored facts that cannot be true) and a storage
 * exception travel as [AdaptiveIntegrationResult.InvalidData] / [AdaptiveIntegrationResult.Failure];
 * a gap is a value a caller can render, count or ignore.
 */
enum class AdaptiveInputGap {

    /**
     * The completion the pass was asked about is not stored.
     *
     * Either the identity is wrong or the workout was never started. Expected: a caller that asks about
     * a session which does not exist has made a request about nothing, not a storage failure.
     */
    NO_SUCH_SESSION,

    /**
     * The attempt ended as a **cancellation**, so there is no completion for it (§6, §19).
     *
     * A cancelled attempt is not a completed workout: it keeps its partial work as exposure for later
     * windows, and it never becomes the completion that adapts anything.
     *
     * An attempt still **in progress** is the opposite case and is *not* a gap: §27's completion is one
     * transaction, so the pass prepares the adaptive half for the very attempt whose completion will
     * write it — its confirmed sets are work that happened, and the end stamp the completion is about to
     * write does not change that.
     */
    SESSION_WAS_CANCELLED,

    /**
     * The Program revision is `MANUAL` (§19).
     *
     * A manual Program is fully user-authored: every structural fact in it is the user's own choice, so
     * automatic adaptation has nothing in it to change. The rule is checked before any element is
     * considered — a manual plan is not *"adapted conservatively"*, it is not adapted at all — and it
     * stacks on top of the engine's own per-element ownership rule (`USER_AUTHORED` / `PINNED`), which
     * decides the same question one level down for a generated plan that contains user-owned elements.
     */
    PROGRAM_MODE_IS_MANUAL,

    /**
     * No future opportunity is eligible (§4, §18).
     *
     * The revision holds no slot that is still ahead of the user: every one is taken, withdrawn, or
     * already being worked out. This is the ordinary end of a program, and it is explicitly **not** an
     * exception: no decision is produced, nothing is written, and the Scheduler is not asked to create
     * a slot (§25 — the adaptive layer changes presentation, not opportunity).
     */
    NO_FUTURE_SLOT,

    /**
     * The target opportunity's plan day presents nothing to adapt.
     *
     * A rest day is a real plan day with no elements (`SessionRuntime` refuses to start one for the same
     * reason), so there is no element and no family to decide about.
     */
    PLAN_DAY_PRESENTS_NOTHING,

    /**
     * No element of the target opportunity belongs to a family the caller classifies.
     *
     * The app stores **no exercise→family catalogue** for the target path
     * ([ExerciseFamilyClassification]), so this is what production reports today. It is deliberately a
     * *different* gap from [NO_DECLARED_PROGRESSION_RELATION]: *"we do not know which family this
     * exercise belongs to"* and *"we know the family and declare no ladder for it"* are fixed by
     * different artefacts, and a single "cannot adapt" would hide which one is missing. Nothing is
     * inferred from an exercise id or a name to bridge either one (§9, §10).
     */
    NO_FAMILY_CLASSIFICATION,

    /**
     * The target day presents none of the families the completed session exposed.
     *
     * The decision is about the family the completion just trained, and its target is that family's next
     * presentation. A day that trains other families is not adapted *now*: the family's own window opens
     * when its next appearance is completed, which is when its evidence has moved on. Adapting some
     * other element instead would be choosing a subject the completion says nothing about.
     */
    NO_EXPOSED_FAMILY_IN_THE_TARGET_SLOT,

    /**
     * The exposed family's ladder is not declared (§10).
     *
     * The family is one the completion trained and one the target day presents, but no
     * [ProgressionRelationProvider] declares its ladder — so the engine has no relation to resolve
     * against and no honest step exists. This is the production state of every family today (see
     * [ProgressionRelationProvider]), and it is recorded as an explicit gap rather than as a fabricated
     * ladder, a substituted exercise or a default level.
     */
    NO_DECLARED_PROGRESSION_RELATION
}
