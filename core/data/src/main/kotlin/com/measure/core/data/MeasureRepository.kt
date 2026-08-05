package com.measure.core.data

import com.measure.core.geometry.Opening
import com.measure.core.geometry.OpeningKind
import com.measure.core.geometry.Polygon
import com.measure.core.geometry.RoomSolution
import com.measure.core.geometry.RoomSurfaces
import com.measure.core.geometry.SurfaceCalculator
import com.measure.core.geometry.Vec2
import com.measure.core.geometry.Vec3
import com.measure.core.geometry.capture.MeasuredSegment
import com.measure.core.geometry.plan.PlanAnchor
import com.measure.core.geometry.plan.PlanMeasurement
import com.measure.core.geometry.plan.PlanSnapper
import com.measure.core.geometry.plan.RoomPlacement
import com.measure.core.geometry.plan.SnapKind
import com.measure.core.geometry.plan.SnapRoom
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
    /** Standalone measurements as plan-view lines, so they appear on the thumbnail too. */
    val measurementLines: List<List<Vec2>>,
    /** The value, when a project holds exactly one measurement and nothing else. */
    val soleMeasurement: Length?,
) {
    val isEmpty: Boolean get() = roomCount == 0 && measurementCount == 0

    /** Everything drawable, for a thumbnail that is never blank when there is work in it. */
    val thumbnailOutlines: List<List<Vec2>> get() = outlines + measurementLines
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
    /** Doors and windows, grouped by the wall index they sit in. */
    val openings: Map<Int, List<SavedOpening>>,
    /**
     * The AR world frame this room was measured in, or empty if it was captured before the
     * app recorded one. Only ever compared, never interpreted.
     */
    val captureSession: String = "",
    /**
     * Corners the rectilinear solve moved.
     *
     * Kept so that anything derived from this room can say when it is standing on a
     * modelled position rather than an observed one — a distance measured to a corner the
     * solver squared up is partly the solver's opinion.
     */
    val snappedCorners: Set<Int> = emptySet(),
) {
    /**
     * Wall area and volume, when a height is known.
     *
     * Null without one rather than substituting a typical 2.4 m: a guessed height would
     * produce a paint estimate indistinguishable from a measured one, and the user would
     * have no way to tell which they were looking at.
     */
    val surfaces: RoomSurfaces?
        get() {
            val height = ceilingHeight ?: return null
            if (outline.size < 3) return null
            return SurfaceCalculator.compute(
                polygon = Polygon(outline),
                ceilingHeight = height,
                openings = openings.values.flatten().map { it.opening },
            )
        }
}

data class SavedOpening(val id: Long, val wallIndex: Int, val opening: Opening)

data class SavedMeasurement(
    val id: Long,
    val mode: MeasurementMode,
    val from: Vec3,
    val to: Vec3,
    val length: Length,
    val sigma: Length,
    val label: String?,
    val createdAt: Long,
) {
    /**
     * Whether this is essentially a height, and so has no length on a floor plan.
     *
     * A plan discards the vertical axis, so a plumb measurement projects onto it as a
     * single point. Anything drawing measurements on a plan has to know that, or a room
     * height appears as a dot — which is what a project holding two of them looked like.
     *
     * Judged as a share of the true length rather than against a fixed distance, so a
     * genuinely short horizontal measurement, the width of a doorframe say, is still the
     * short line it is. The mode is not enough on its own: a free measurement taken up a
     * wall is just as vertical as a plumb one, and only the geometry says so.
     */
    val isVerticalOnPlan: Boolean
        get() {
            val planLength = from.toFloorPlane().distanceTo(to.toFloorPlane())
            return planLength < DEGENERATE_PLAN_METRES ||
                (length.metres > 0.0 && planLength < length.metres * VERTICAL_PLAN_SHARE)
        }

    private companion object {
        /** Shorter than this on the plan and there is nothing to draw a line between. */
        const val DEGENERATE_PLAN_METRES = 0.02

        /** Below this share of its true length, a measurement is a height, not a distance. */
        const val VERTICAL_PLAN_SHARE = 0.25
    }
}

/**
 * A distance drawn on the plan, resolved against the plan as it currently is.
 *
 * [measurement] is null when an end was anchored to geometry that has since gone — a
 * corner index past the end of a re-solved room, say. The row is kept rather than deleted
 * so that undoing the edit brings the measurement back, and the editor simply does not
 * draw one it cannot place.
 */
data class SavedPlanMeasurement(
    val id: Long,
    val measurement: PlanMeasurement?,
    val label: String?,
    val createdAt: Long,
)

data class ProjectDetail(
    val id: Long,
    val name: String,
    val unitSystem: UnitSystem,
    val rooms: List<SavedRoom>,
    val measurements: List<SavedMeasurement>,
    val planMeasurements: List<SavedPlanMeasurement> = emptyList(),
) {
    /** The rooms in the form [PlanSnapper] wants, so callers do not each build it. */
    val snapRooms: List<SnapRoom> get() = rooms.map(SavedRoom::toSnapRoom)

    /**
     * Whether this plan holds rooms measured in more than one AR session.
     *
     * When it does, **how the rooms sit relative to each other was never measured** — each
     * session gave its own origin, so the app set the later rooms down beside the earlier
     * ones and the arrangement is a layout, not a survey. Inside any one room every number
     * is as good as it ever was.
     *
     * The screen has to say this. A floor plan's whole claim is that it describes a
     * building, and a user who does not know which parts of that claim were measured has
     * no way to tell a real 4.2 m gap between two rooms from an arbitrary one.
     */
    val hasUnrelatedCaptures: Boolean
        get() = rooms.map { it.captureSession }.distinct().size > 1
}

internal fun SavedRoom.toSnapRoom() = SnapRoom(
    id = id,
    label = name,
    outline = outline,
    cornerSigmas = sigmas,
    snappedCorners = snappedCorners,
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
    private val openings = database.openingDao()
    private val measurements = database.measurementDao()
    private val planMeasurements = database.planMeasurementDao()

    // --- projects -------------------------------------------------------------------

    fun observeProjects(): Flow<List<ProjectSummary>> =
        combine(
            projects.observeSummaries(),
            projects.observeOutlinePoints(),
            projects.observeMeasurementLines(),
        ) { summaries, points, lines ->
            val outlinesByProject = points
                .groupBy { it.projectId }
                .mapValues { (_, rows) -> rows.groupBy { it.roomId }.values.map { room -> room.map { Vec2(it.x, it.y) } } }

            val linesByProject = lines.groupBy { it.projectId }

            summaries.map { row ->
                val projectLines = linesByProject[row.id].orEmpty()
                ProjectSummary(
                    id = row.id,
                    name = row.name,
                    updatedAt = row.updatedAt,
                    unitSystem = row.unitSystem.toUnitSystem(),
                    roomCount = row.roomCount,
                    measurementCount = row.measurementCount,
                    totalArea = Area(row.totalArea),
                    outlines = outlinesByProject[row.id].orEmpty(),
                    measurementLines = projectLines.map {
                        // Vec3.toFloorPlane negates z, so the plan matches the AR frame.
                        listOf(Vec2(it.fromX, -it.fromZ), Vec2(it.toX, -it.toZ))
                    },
                    soleMeasurement = projectLines.singleOrNull()
                        ?.takeIf { row.roomCount == 0 }
                        ?.let { Length(it.metres) },
                )
            }
        }

    fun observeProject(projectId: Long): Flow<ProjectDetail?> =
        combine(
            projects.observe(projectId),
            rooms.observeRooms(projectId),
            rooms.observeCorners(projectId),
            walls.observeFor(projectId),
            openings.observeFor(projectId),
            measurements.observeFor(projectId),
            planMeasurements.observeFor(projectId),
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val project = values[0] as ProjectEntity?
            @Suppress("UNCHECKED_CAST")
            val roomRows = values[1] as List<RoomEntity>
            @Suppress("UNCHECKED_CAST")
            val cornerRows = values[2] as List<CornerEntity>
            @Suppress("UNCHECKED_CAST")
            val wallRows = values[3] as List<WallEntity>
            @Suppress("UNCHECKED_CAST")
            val openingRows = values[4] as List<OpeningEntity>
            @Suppress("UNCHECKED_CAST")
            val measurementRows = values[5] as List<MeasurementEntity>
            @Suppress("UNCHECKED_CAST")
            val planRows = values[6] as List<PlanMeasurementEntity>

            if (project == null) return@combine null
            val cornersByRoom = cornerRows.groupBy { it.roomId }
            val wallsByRoom = wallRows.groupBy { it.roomId }
            val openingsByRoom = openingRows.groupBy { it.roomId }
            ProjectDetail(
                id = project.id,
                name = project.name,
                unitSystem = project.unitSystem.toUnitSystem(),
                rooms = roomRows.map {
                    it.toSavedRoom(
                        corners = cornersByRoom[it.id].orEmpty(),
                        walls = wallsByRoom[it.id].orEmpty(),
                        openings = openingsByRoom[it.id].orEmpty(),
                    )
                },
                measurements = measurementRows.map { it.toSavedMeasurement() },
                planMeasurements = planRows.map { row ->
                    // Resolved here rather than in the editor so that every reader sees a
                    // measurement placed against the same geometry the plan is drawn from.
                    val snapRooms = roomRows.map {
                        it.toSavedRoom(
                            corners = cornersByRoom[it.id].orEmpty(),
                            walls = wallsByRoom[it.id].orEmpty(),
                            openings = openingsByRoom[it.id].orEmpty(),
                        ).toSnapRoom()
                    }
                    row.toSavedPlanMeasurement(snapRooms)
                },
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
     *
     * @param captureSession which AR world frame these coordinates are in. When it differs
     *   from every room already in the project, the room is **moved clear of them** before
     *   being stored: its coordinates and theirs came from different ARCore sessions and
     *   have no common origin, so leaving them as they are would draw an overlap or a gap
     *   that nobody measured. See [RoomPlacement]. Empty means unknown, and nothing moves,
     *   because a guess is only worth making from evidence.
     */
    suspend fun saveRoom(
        projectId: Long,
        name: String,
        solution: RoomSolution,
        measured: List<Vec2>,
        sigmas: List<Double>,
        ceilingHeight: Double? = null,
        captureSession: String = "",
    ): Long {
        @Suppress("NAME_SHADOWING") val ceilingHeight = ceilingHeight?.takeIf { it > 0.0 }
        val level = levels.firstFor(projectId)
            ?: LevelEntity(id = levels.insert(LevelEntity(projectId = projectId, name = "Ground floor", elevation = 0.0)), projectId = projectId, name = "Ground floor", elevation = 0.0)

        val offset = placementOffset(projectId, captureSession, solution.polygon.vertices)

        val roomId = rooms.insert(
            RoomEntity(
                levelId = level.id,
                name = name,
                ceilingHeight = ceilingHeight,
                captureSession = captureSession,
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
                    x = vertex.x + offset.x,
                    y = vertex.y + offset.y,
                    // The observations move with the solution, and must: a re-solve starts
                    // from them, so leaving them behind would teleport the room back to
                    // its capture coordinates the first time a wall was locked.
                    measuredX = measured.getOrElse(index) { vertex }.x + offset.x,
                    measuredY = measured.getOrElse(index) { vertex }.y + offset.y,
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

    /**
     * How far this room has to move to be honest about not knowing where it is.
     *
     * Zero unless the project already holds rooms from a *different* world frame. Zero also
     * when the frame is unknown at either end, because "these might be the same session"
     * is not grounds for shoving a room across the plan.
     */
    private suspend fun placementOffset(
        projectId: Long,
        captureSession: String,
        vertices: List<Vec2>,
    ): Vec2 {
        if (captureSession.isEmpty()) return Vec2.ZERO
        val existing = rooms.roomsIn(projectId)
        if (existing.isEmpty()) return Vec2.ZERO
        if (existing.any { it.captureSession == captureSession }) return Vec2.ZERO

        val occupied = rooms.cornersIn(projectId).map { Vec2(it.x, it.y) }
        return RoomPlacement.offsetFor(occupied, vertices)
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

    // --- openings and heights -------------------------------------------------------

    suspend fun addOpening(roomId: Long, wallIndex: Int, opening: Opening): Long =
        openings.insert(
            OpeningEntity(
                roomId = roomId,
                index = wallIndex,
                kind = opening.kind.name,
                offset = opening.offset,
                width = opening.width,
                height = opening.height,
                sillHeight = opening.sillHeight,
            ),
        )

    suspend fun updateOpening(id: Long, roomId: Long, wallIndex: Int, opening: Opening) =
        openings.update(
            OpeningEntity(
                id = id,
                roomId = roomId,
                index = wallIndex,
                kind = opening.kind.name,
                offset = opening.offset,
                width = opening.width,
                height = opening.height,
                sillHeight = opening.sillHeight,
            ),
        )

    suspend fun deleteOpening(id: Long) = openings.delete(id)

    suspend fun setCeilingHeight(roomId: Long, metres: Double?) =
        rooms.setCeilingHeight(roomId, metres)

    /** Locks a wall to a hand-measured length, or unlocks it when [metres] is null. */
    suspend fun setLockedLength(roomId: Long, wallIndex: Int, metres: Double?) {
        if (metres == null) {
            walls.unlock(roomId, wallIndex)
        } else {
            walls.upsert(WallEntity(roomId = roomId, index = wallIndex, lockedLength = metres))
        }
    }

    /**
     * Deletes a room, and anything on the plan that was measured to it.
     *
     * A distance anchored to a room that no longer exists is not a distance to anything.
     * Leaving it would put a labelled line on the plan pointing into empty space, which
     * is worse than losing the measurement.
     */
    suspend fun deleteRoom(roomId: Long) {
        planMeasurements.deleteForRoom(roomId)
        rooms.delete(roomId)
    }

    suspend fun renameRoom(roomId: Long, name: String) = rooms.rename(roomId, name)

    /**
     * Slides a whole room across the plan, without changing its shape.
     *
     * The counterpart to [RoomPlacement]: the app sets a room down clear of the others
     * because it does not know where it goes, and this is how the person who does know
     * says so. A plain translation of every corner, so the room the user measured is the
     * room they still have — no re-solve, no snapping, nothing that could quietly alter a
     * wall length while they were arranging the drawing.
     *
     * Observations move with the solution. They are what a later re-solve starts from, so
     * leaving them behind would spring the room back the first time a wall was locked.
     */
    suspend fun moveRoom(roomId: Long, projectId: Long, dx: Double, dy: Double) {
        if (dx == 0.0 && dy == 0.0) return
        rooms.translateCorners(roomId, dx, dy)
        projects.touch(projectId, now())
    }

    suspend fun deleteMeasurement(measurementId: Long) = measurements.deleteById(measurementId)

    // --- distances drawn on the plan ---------------------------------------------------

    suspend fun savePlanMeasurement(
        projectId: Long,
        from: PlanAnchor,
        to: PlanAnchor,
        label: String? = null,
    ): Long {
        val id = planMeasurements.insert(
            PlanMeasurementEntity(
                projectId = projectId,
                fromKind = from.kindName(),
                fromRoomId = from.roomIdOrNull(),
                fromIndex = from.indexOrZero(),
                fromT = from.tOrZero(),
                fromX = from.freeOrZero().x,
                fromY = from.freeOrZero().y,
                toKind = to.kindName(),
                toRoomId = to.roomIdOrNull(),
                toIndex = to.indexOrZero(),
                toT = to.tOrZero(),
                toX = to.freeOrZero().x,
                toY = to.freeOrZero().y,
                label = label,
                createdAt = now(),
            ),
        )
        projects.touch(projectId, now())
        return id
    }

    suspend fun deletePlanMeasurement(id: Long) = planMeasurements.deleteById(id)

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
    openings: List<OpeningEntity> = emptyList(),
): SavedRoom {
    val ordered = corners.sortedBy { it.index }
    return SavedRoom(
        snappedCorners = ordered.filter { it.isSnapped }.map { it.index }.toSet(),
        id = id,
        name = name,
        outline = ordered.map { Vec2(it.x, it.y) },
        measured = ordered.map { Vec2(it.measuredX, it.measuredY) },
        sigmas = ordered.map { it.sigma },
        lockedLengths = walls.associate { it.index to it.lockedLength },
        openings = openings.groupBy { it.index }
            .mapValues { (_, rows) -> rows.map { it.toSavedOpening() } },
        area = Area(area),
        perimeter = Length(perimeter),
        misclosure = misclosure,
        isReliable = isReliable,
        ceilingHeight = ceilingHeight,
        captureSession = captureSession,
    )
}

internal fun OpeningEntity.toSavedOpening() = SavedOpening(
    id = id,
    wallIndex = index,
    opening = Opening(
        kind = runCatching { OpeningKind.valueOf(kind) }.getOrDefault(OpeningKind.DOOR),
        offset = offset,
        width = width,
        height = height,
        sillHeight = sillHeight,
    ),
)

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

// --- plan measurement anchors ------------------------------------------------------------

private fun PlanAnchor.kindName(): String = when (this) {
    is PlanAnchor.Corner -> SnapKind.CORNER.name
    is PlanAnchor.Wall -> SnapKind.WALL.name
    is PlanAnchor.Free -> SnapKind.FREE.name
}

private fun PlanAnchor.roomIdOrNull(): Long? = when (this) {
    is PlanAnchor.Corner -> roomId
    is PlanAnchor.Wall -> roomId
    is PlanAnchor.Free -> null
}

private fun PlanAnchor.indexOrZero(): Int = when (this) {
    is PlanAnchor.Corner -> index
    is PlanAnchor.Wall -> index
    is PlanAnchor.Free -> 0
}

private fun PlanAnchor.tOrZero(): Double = if (this is PlanAnchor.Wall) t else 0.0

private fun PlanAnchor.freeOrZero(): Vec2 = if (this is PlanAnchor.Free) position else Vec2.ZERO

private fun anchorFrom(kind: String, roomId: Long?, index: Int, t: Double, x: Double, y: Double): PlanAnchor =
    when (runCatching { SnapKind.valueOf(kind) }.getOrDefault(SnapKind.FREE)) {
        SnapKind.CORNER -> roomId?.let { PlanAnchor.Corner(it, index) } ?: PlanAnchor.Free(Vec2(x, y))
        SnapKind.WALL -> roomId?.let { PlanAnchor.Wall(it, index, t) } ?: PlanAnchor.Free(Vec2(x, y))
        SnapKind.FREE -> PlanAnchor.Free(Vec2(x, y))
    }

internal fun PlanMeasurementEntity.toSavedPlanMeasurement(rooms: List<SnapRoom>): SavedPlanMeasurement {
    val from = PlanSnapper.resolve(rooms, anchorFrom(fromKind, fromRoomId, fromIndex, fromT, fromX, fromY))
    val to = PlanSnapper.resolve(rooms, anchorFrom(toKind, toRoomId, toIndex, toT, toX, toY))
    return SavedPlanMeasurement(
        id = id,
        measurement = if (from != null && to != null) PlanMeasurement(from, to) else null,
        label = label,
        createdAt = createdAt,
    )
}
