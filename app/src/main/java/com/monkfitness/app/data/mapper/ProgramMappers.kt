package com.monkfitness.app.data.mapper

import com.monkfitness.app.data.model.ProgramEntity
import com.monkfitness.app.domain.common.ProgramId
import com.monkfitness.app.domain.common.RevisionId
import com.monkfitness.app.domain.program.LifecycleStatus
import com.monkfitness.app.domain.program.Program
import com.monkfitness.app.domain.program.ProgramSource

/**
 * `program` row ⇄ `Program`, and nothing else.
 *
 * The row is the Program's identity and its non-structural facts; it carries no plan, no mode and no
 * selection (§23), so this mapper never loads a revision and never fabricates one: the aggregate a
 * caller may need — a Program together with its current revision — is assembled by the repository
 * that deliberately loads both, not here. A mapper that fetched a revision would be a hidden database
 * call inside a value translation.
 *
 * Both directions are total and deterministic: every stored field is mapped, the two vocabulary
 * columns are resolved against the domain enums by name, and no clock, no id generation and no
 * default value appears. Domain invariants (a named Program, a lifecycle that agrees with when it
 * actually started, an archive stamp that cannot precede creation) are enforced by the `Program`
 * constructor, so a row that violates one fails the load instead of loading "approximately".
 */

/** The domain value of one stored Program row. */
internal fun ProgramEntity.toDomain(): Program = Program(
    programId = ProgramId(programId),
    name = name,
    description = description,
    source = storedToken(source, ProgramSource.entries, "program.source"),
    lifecycleStatus = storedToken(lifecycleStatus, LifecycleStatus.entries, "program.lifecycleStatus"),
    currentRevisionId = RevisionId(currentRevisionId),
    createdAt = storedInstant("program.createdAt", createdAt),
    updatedAt = storedInstant("program.updatedAt", updatedAt),
    plannedStartDate = plannedStartDate?.let { storedDate("program.plannedStartDate", it) },
    actualStartDate = actualStartDate?.let { storedInstant("program.actualStartDate", it) },
    archivedAt = archivedAt?.let { storedInstant("program.archivedAt", it) }
)

/** The stored representation of one Program. */
internal fun Program.toEntity(): ProgramEntity = ProgramEntity(
    programId = programId.value,
    name = name,
    description = description,
    source = source.name,
    lifecycleStatus = lifecycleStatus.name,
    currentRevisionId = currentRevisionId.value,
    createdAt = storedMilliseconds(createdAt),
    updatedAt = storedMilliseconds(updatedAt),
    plannedStartDate = plannedStartDate?.let { storedDateValue(it) },
    actualStartDate = actualStartDate?.let { storedMilliseconds(it) },
    archivedAt = archivedAt?.let { storedMilliseconds(it) }
)
