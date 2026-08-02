package com.measure.core.data

import com.measure.core.geometry.RoomSolution
import com.measure.core.geometry.Vec2
import com.measure.core.geometry.Vec3
import com.measure.core.geometry.capture.MeasuredSegment
import com.measure.core.geometry.capture.MeasurementMode
import com.measure.core.units.Area
import com.measure.core.units.Length
import com.measure.core.units.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

// --- what the rest of the app sees -----------------------------------------------------
//
// Room entities stay inside this module. A feature module that imported them would end up
// with the database's shape baked into its UI, and every schema change would then be a UI
// change too.

data class ProjectSummary(
    val id: Long,
    val name: String,
    val updatedAt: Long,
    val unitSystem: UnitSystem,
    val roomCount: Int,
    val measurementCount: Int,
    val totalArea: Area,
    /** One outline per room, in capture order. Enough to draw a plan thumbnail. */
    val outlines: List<List<Vec2>>,
) {
    val isEmpty: Boolean get() = roomCount == 0 && measurementCount == 0
}

data class SavedRoom(
    val id: Long,
    val name: String,
    /** The solved outline. What the plan draws. */
    val outline: List<Vec2>,
    /** The observed corners. What every re-solve starts from, so edits stay idempotent. */
    val measured: List<Vec2>,
    /** Per-corner uncertainty, kept so a later re-solve can weight as well as the first. */
    val sigmas: List<Double>,
    /** Wall index to the length the user measured by hand, for the walls they locked. */
    val lockedLengths: Map<Int, Double>,
    val area: Area,
    val perimeter: Length,
    val misclosure: Double,
    val isReliable: Boolean,
    val ceilingHeight: Double?,
)

data class SavedMeasurement(
    val id: Long,
    val mode: MeasurementMode,
    val from: Vec3,
    val to: Vec3,
    val length: Length,
    val sigma: Length,
    val label: String?,
    val createdAt: Long,
)

data class ProjectDetail(
    val id: Long,
    val name: String,
    val unitSystem: UnitSystem,
    val rooms: List<SavedRoom>,
    val measurements: List<SavedMeasurement>,
)

/**
 * The only way in and out of the database.
 *
 * Everything here is suspending or a `Flow`, and writes are small and immediate: this app
 * has no save button. A measurement that took thirty seconds of walking should survive
 * the phone ringing, and the way to guarantee that is to have already written it.
 */
class MeasureRepository(
    private val database: MeasureDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val projects = database.projectDao()
    private val levels = database.levelDao()
    private val rooms = database.roomDao()
    private val walls = database.wallDao()
    private val measurements = database.measurementDao()

    // --- projects -------------------------------------------------------------------

    fun observeProjects(): Flow<List<ProjectSummary>> =
        combine(projects.observeSummaries(), projects.observeOutlinePoints()) { summaries, points ->
            val outlinesByProject = points
                .groupBy { it.projectId }
                .mapValues { (_, rows) -> rows.groupBy { it.roomId }.values.map { room -> room.map { Vec2(it.x, it.y) } } }

            summaries.map { row ->
                ProjectSummary(
                    id = row.id,
                    name = row.name,
                    updatedAt = row.updatedAt,
                    unitSystem = row.unitSystem.toUnitSystem(),
                    roomCount = row.roomCount,
                    measurementCount = row.measurementCount,
                    totalArea = Area(row.totalArea),
                    outlines = outlinesByProject[row.id].orEmpty(),
                )
            }
        }

    fun observeProject(projectId: Long): Flow<ProjectDetail?> =
        combine(
            projects.observe(projectId),
            rooms.observeRooms(projectId),
            rooms.observeCorners(projectId),
            walls.observeFor(projectId),
            measurements.observeFor(projectId),
        ) { project, roomRows, cornerRows, wallRows, measurementRows ->
            if (project == null) return@combine null
            val cornersByRoom = cornerRows.groupBy { it.roomId }
            val wallsByRoom = wallRows.groupBy { it.roomId }
            ProjectDetail(
                id = project.id,
                name = project.name,
                unitSystem = project.unitSystem.toUnitSystem(),
                rooms = roomRows.map {
                    it.toSavedRoom(
                        corners = cornersByRoom[it.id].orEmpty(),
                        walls = wallsByRoom[it.id].orEmpty(),
                    )
                },
                measurements = measurementRows.map { it.toSavedMeasurement() },
            )
        }

    /**
     * Creates a project and its ground level in one go.
     *
     * Every project has at least one level, so that rooms always have somewhere to live
     * and no caller has to remember to create one. Multi-storey capture makes the extra
     * levels; nothing else ever has to think about them.
     */
    suspend fun createProject(name: String, unitSystem: UnitSystem = UnitSystem.METRIC): Long {
        val stamp = now()
        val projectId = projects.insert(
            ProjectEntity(name = name, createdAt = stamp, updatedAt = stamp, unitSystem = unitSystem.name),
        )
        levels.insert(LevelEntity(projectId = projectId, name = "Ground floor", elevation = 0.0))
        return projectId
    }

    /** Creates a project named "Plan N", filling the lowest free number. */
    suspend fun createDefaultProject(unitSystem: UnitSystem = UnitSystem.METRIC): Long =
        createProject(ProjectNaming.nextProjectName(projects.allNames()), unitSystem)

    suspend fun nextRoomName(projectId: Long): String =
        ProjectNaming.nextRoomName(rooms.namesIn(projectId))

    suspend fun renameProject(projectId: Long, name: String) = projects.rename(projectId, name, now())

    suspend fun deleteProject(projectId: Long) = projects.delete(projectId)

    suspend fun setUnitSystem(projectId: Long, unitSystem: UnitSystem) {
        val project = projects.find(projectId) ?: return
        projects.update(project.copy(unitSystem = unitSystem.name, updatedAt = now()))
    }

    // --- saving captures ------------------------------------------------------------

    /**
     * Persists a solved room. Returns its id.
     *
     * The *solved* polygon is what gets stored, not the raw taps, because the solution is
     * what the user was shown and agreed to. The per-corner sigmas travel with it so a
     * future re-solve — after an edit, or a locked wall length — can weight the corners
     * exactly as the first solve did.
     */
    suspend fun saveRoom(
        projectId: Long,
        name: String,
        solution: RoomSolution,
        measured: List<Vec2>,
        sigmas: List<Double>,
        ceilingHeight: Double? = null,
    ): Long {
        val level = levels.firstFor(projectId)
            ?: LevelEntity(id = levels.insert(LevelEntity(projectId = projectId, name = "Ground floor", elevation = 0.0)), projectId = projectId, name = "Ground floor", elevation = 0.0)

        val roomId = rooms.insert(
            RoomEntity(
                levelId = level.id,
                name = name,
                area = solution.area.squareMetres,
                perimeter = solution.perimeter.metres,
                misclosure = solution.closure.relativeError,
                isReliable = solution.isReliable,
                createdAt = now(),
            ),
        )

        rooms.insertCorners(
            solution.polygon.vertices.mapIndexed { index, vertex ->
                CornerEntity(
                    roomId = roomId,
                    index = index,
                    x = vertex.x,
                    y = vertex.y,
                    measuredX = measured.getOrElse(index) { vertex }.x,
                    measuredY = measured.getOrElse(index) { vertex }.y,
                    sigma = sigmas.getOrElse(index) { DEFAULT_SIGMA },
                    // A corner counts as snapped when either wall meeting there was
                    // pulled to an axis, since it is the corner that moved to make that
                    // happen. Edge i runs from corner i to corner i + 1.
                    isSnapped = solution.snap.isCornerSnapped(index, solution.polygon.size),
                )
            },
        )

        projects.touch(projectId, now())
        return roomId
    }

    suspend fun saveMeasurement(projectId: Long, segment: MeasuredSegment, label: String? = null): Long {
        val id = measurements.insert(
            MeasurementEntity(
                projectId = projectId,
                mode = segment.mode.name,
                fromX = segment.from.position.x,
                fromY = segment.from.position.y,
                fromZ = segment.from.position.z,
                toX = segment.to.position.x,
                toY = segment.to.position.y,
                toZ = segment.to.position.z,
                metres = segment.lengthMetres,
                sigma = segment.sigmaMetres,
                label = label,
                createdAt = now(),
            ),
        )
        projects.touch(projectId, now())
        return id
    }

    // --- editing --------------------------------------------------------------------

    /**
     * Replaces a room's geometry after an edit.
     *
     * The corners are rewritten wholesale rather than updated in place. A room has a
     * handful of them, the solve moves all of them at once, and matching them up by index
     * to issue individual updates would be more code for no benefit. Sigmas carry over
     * unchanged: an edit does not make the original observations better or worse.
     */
    suspend fun updateRoomGeometry(
        roomId: Long,
        solution: RoomSolution,
        measured: List<Vec2>,
        sigmas: List<Double>,
    ) {
        rooms.deleteCorners(roomId)
        rooms.insertCorners(
            solution.polygon.vertices.mapIndexed { index, vertex ->
                CornerEntity(
                    roomId = roomId,
                    index = index,
                    x = vertex.x,
                    y = vertex.y,
                    measuredX = measured.getOrElse(index) { vertex }.x,
                    measuredY = measured.getOrElse(index) { vertex }.y,
                    sigma = sigmas.getOrElse(index) { DEFAULT_SIGMA },
                    isSnapped = solution.snap.isCornerSnapped(index, solution.polygon.size),
                )
            },
        )
        rooms.updateGeometry(
            id = roomId,
            area = solution.area.squareMetres,
            perimeter = solution.perimeter.metres,
            reliable = solution.isReliable,
        )
    }

    /** Locks a wall to a hand-measured length, or unlocks it when [metres] is null. */
    suspend fun setLockedLength(roomId: Long, wallIndex: Int, metres: Double?) {
        if (metres == null) {
            walls.unlock(roomId, wallIndex)
        } else {
            walls.upsert(WallEntity(roomId = roomId, index = wallIndex, lockedLength = metres))
        }
    }

    suspend fun deleteRoom(roomId: Long) = rooms.delete(roomId)

    suspend fun renameRoom(roomId: Long, name: String) = rooms.rename(roomId, name)

    suspend fun deleteMeasurement(measurementId: Long) = measurements.deleteById(measurementId)

    private companion object {
        /** Only reached if a solution arrives with fewer sigmas than corners. */
        const val DEFAULT_SIGMA = 0.02
    }
}

private fun com.measure.core.geometry.SnapResult?.isCornerSnapped(index: Int, cornerCount: Int): Boolean {
    val snaps = this?.snaps ?: return false
    val incoming = snaps.getOrNull((index - 1 + cornerCount) % cornerCount)?.isSnapped ?: false
    val outgoing = snaps.getOrNull(index)?.isSnapped ?: false
    return incoming || outgoing
}

// --- mapping ---------------------------------------------------------------------------

internal fun String.toUnitSystem(): UnitSystem =
    runCatching { UnitSystem.valueOf(this) }.getOrDefault(UnitSystem.METRIC)

internal fun RoomEntity.toSavedRoom(
    corners: List<CornerEntity>,
    walls: List<WallEntity> = emptyList(),
): SavedRoom {
    val ordered = corners.sortedBy { it.index }
    return SavedRoom(
        id = id,
        name = name,
        outline = ordered.map { Vec2(it.x, it.y) },
        measured = ordered.map { Vec2(it.measuredX, it.measuredY) },
        sigmas = ordered.map { it.sigma },
        lockedLengths = walls.associate { it.index to it.lockedLength },
        area = Area(area),
        perimeter = Length(perimeter),
        misclosure = misclosure,
        isReliable = isReliable,
        ceilingHeight = ceilingHeight,
    )
}

internal fun MeasurementEntity.toSavedMeasurement() = SavedMeasurement(
    id = id,
    mode = runCatching { MeasurementMode.valueOf(mode) }.getOrDefault(MeasurementMode.FREE),
    from = Vec3(fromX, fromY, fromZ),
    to = Vec3(toX, toY, toZ),
    length = Length(metres),
    sigma = Length(sigma),
    label = label,
    createdAt = createdAt,
)
