package com.monkfitness.app.domain.program

/** Pure execution contracts for Stage 1; no Room, clock, repository, or adaptive state machine. */
enum class WorkRequirement { REQUIRED, OPTIONAL }

data class PlannedWork(
    val workId: String,
    val requirement: WorkRequirement,
    val target: Int
) {
    init {
        require(workId.isNotBlank()) { "planned work needs an identity" }
        require(target >= 0) { "target cannot be negative" }
    }
}

sealed interface PerformedWork {
    val value: Int

    data class Repetitions(override val value: Int) : PerformedWork {
        init { require(value > 0) { "performed repetitions must be positive" } }
    }

    data class Seconds(override val value: Int) : PerformedWork {
        init { require(value > 0) { "performed seconds must be positive" } }
    }

    companion object {
        fun reps(value: Int): PerformedWork = Repetitions(value)
        fun seconds(value: Int): PerformedWork = Seconds(value)
    }
}

data class ActualResult(val workId: String, val work: PerformedWork) {
    init { require(workId.isNotBlank()) { "actual work needs an identity" } }

    val isExplicitlyPerformed: Boolean get() = true

    companion object {
        fun fromPerformed(workId: String, work: PerformedWork): ActualResult =
            ActualResult(workId, work)
    }
}

enum class CompletionAssessment { COMPLETED, INCOMPLETE }

data class CompletionContract(
    val state: CompletionAssessment,
    val incompleteRequired: List<String>,
    val incompleteOptional: List<String>
)

object CompletionCalculator {
    fun assess(
        planned: List<PlannedWork>,
        actuals: List<ActualResult>
    ): CompletionContract {
        val actualById = actuals.associateBy { it.workId }
        val incomplete = planned.filter { it.workId !in actualById }
        return CompletionContract(
            state = if (incomplete.none { it.requirement == WorkRequirement.REQUIRED }) {
                CompletionAssessment.COMPLETED
            } else {
                CompletionAssessment.INCOMPLETE
            },
            incompleteRequired = incomplete.filter { it.requirement == WorkRequirement.REQUIRED }.map { it.workId },
            incompleteOptional = incomplete.filter { it.requirement == WorkRequirement.OPTIONAL }.map { it.workId }
        )
    }
}

enum class OccurrenceExecution { PLANNED, STARTED, COMPLETED, CANCELLED }

data class ExecutionEvidence(
    val workId: String,
    val work: PerformedWork,
    val isComplete: Boolean
) {
    val isValidEvidence: Boolean get() = true
    val impliesAutomaticRegress: Boolean get() = false

    companion object {
        fun partial(workId: String, work: PerformedWork): ExecutionEvidence =
            ExecutionEvidence(workId, work, isComplete = false)
    }
}
