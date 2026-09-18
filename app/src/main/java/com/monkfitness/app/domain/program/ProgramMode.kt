package com.monkfitness.app.domain.program

/**
 * The two permanent Program modes (§2).
 *
 * `MANUAL` is fully free-form: arbitrary duration, named days, schedule and exercise order,
 * per-set prescriptions, the same exercise used repeatedly, and no generator limits. `GENERATED`
 * means the generator built the plan but the plan is the user's — manual edits inside a generated
 * Program stay generated and are recorded as overrides, and pins survive regeneration.
 *
 * There is deliberately **no third mode**. "Semi-automatic" is a creation and editing workflow
 * (build it for me, then adjust), not a mode a Program can be in; adding one would immediately
 * create a state whose rules nobody has agreed on. Switching mode is a structural change and
 * therefore creates a new revision (§2, §6) — the mode lives on the revision, not on the Program.
 *
 * Values are persisted by name.
 */
enum class ProgramMode {
    MANUAL,
    GENERATED
}
