package com.measure.feature.capture

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.measure.ar.ArScene
import com.measure.ar.ArSegment
import com.measure.ar.CaptureMode
import com.measure.ar.CornerMethod
import com.measure.ar.MeasureArController
import com.measure.core.data.MeasureData
import com.measure.core.data.MeasureRepository
import com.measure.core.geometry.CapturedCorner
import com.measure.core.geometry.RoomCapture
import com.measure.core.geometry.RoomSolution
import com.measure.core.geometry.RoomSolver
import com.measure.core.geometry.Vec2
import com.measure.core.geometry.capture.CaptureOutcome
import com.measure.core.geometry.capture.ClosingIntent
import com.measure.core.geometry.capture.LoopClosure
import com.measure.core.geometry.capture.MeasuredSegment
import com.measure.core.geometry.capture.MeasurementMode
import com.measure.core.geometry.capture.SampledPoint
import com.measure.core.geometry.capture.WallCaptureOutcome
import com.measure.core.geometry.capture.WallChain
import com.measure.core.geometry.capture.WallFace
import com.measure.core.geometry.capture.WallPairProblem
import com.measure.core.units.LengthFormatter
import com.measure.core.units.UnitSystem
import kotlinx.coroutines.launch

/** A transient message for the user. Advice is neutral; a warning means something went wrong. */
sealed interface CaptureNotice {
    val text: String

    data class Advice(override val text: String) : CaptureNotice
    data class Warning(override val text: String) : CaptureNotice
}

/**
 * Holds the measurement session: what has been measured, what is half-measured, and how
 * it should be displayed.
 *
 * This deliberately owns the [MeasureArController] too, so the ARCore session survives a
 * configuration change instead of being torn down and rebuilt — rebuilding costs the user
 * every plane ARCore had found and several seconds of re-initialisation.
 */
class CaptureViewModel(application: Application) : AndroidViewModel(application) {

    val controller = MeasureArController(application)

    private val repository: MeasureRepository = MeasureData.repository(application)

    /**
     * The project everything captured here is saved into.
     *
     * Created lazily, on the first thing worth saving. Opening the camera and changing
     * your mind should not leave an empty plan in the list to be tidied up later.
     */
    var projectId: Long? = null
        private set

    /** Set when the screen is opened from an existing project, before anything is saved. */
    fun attachToProject(existing: Long?) {
        if (existing != null && projectId == null) projectId = existing
    }

    var mode by mutableStateOf(MeasurementMode.FREE)
        private set

    var unitSystem by mutableStateOf(UnitSystem.METRIC)
        private set

    var showPlanes by mutableStateOf(true)
        private set

    /** The first point of a measurement in progress, waiting for its partner. */
    var pending by mutableStateOf<SampledPoint?>(null)
        private set

    var notice by mutableStateOf<CaptureNotice?>(null)
        private set

    val segments = mutableStateListOf<MeasuredSegment>()

    // --- room capture ---------------------------------------------------------------

    /**
     * Room, not distance. "How big is this room?" is the first use case in the product
     * plan and the reason most people open a measuring app at all; a one-off distance is
     * the thing you reach for second.
     */
    var captureMode by mutableStateOf(CaptureMode.ROOM)
        private set

    /** Corners in walk order, each already projected onto the floor plane by `:ar`. */
    val roomCorners = mutableStateListOf<SampledPoint>()

    /**
     * How corners are being obtained — docs/ACCURACY.md M10.
     *
     * Per room rather than per wall. The accuracy document wants the two mixed within one
     * capture, and that is right, but a chain of walls and a list of tapped points are
     * different structures: every wall after the first serves two corners, which a tapped
     * point never does. Mixing them means a corner list whose entries have different
     * provenance and different neighbours, and getting that wrong would produce plans
     * that are quietly wrong rather than visibly wrong. Recorded in DEVELOPMENT.md §8.
     */
    var cornerMethod by mutableStateOf(CornerMethod.TAP_FLOOR)
        private set

    /** Wall-face capture: the walls taken so far, in the order they were taken. */
    val capturedWalls = mutableStateListOf<WallFace>()

    val isWallMode: Boolean get() = cornerMethod == CornerMethod.WALL_FACES

    /**
     * The ceiling height ARCore has seen, if any.
     *
     * Latched rather than read at the moment of closing: the ceiling is usually noticed
     * in passing, somewhere in the middle of the walk, and by the time the loop closes
     * the phone is pointing at the floor again.
     */
    var detectedCeilingHeight by mutableStateOf<Double?>(null)
        private set

    fun noteCeilingHeight(metres: Double?) {
        if (metres != null && metres > 0.0) detectedCeilingHeight = metres
    }

    /** Non-null once the perimeter is closed and the correction pipeline has run. */
    var roomSolution by mutableStateOf<RoomSolution?>(null)
        private set

    /** The database row the current solution was written to, so undo can take it back. */
    private var savedRoomId: Long? = null

    val isRoomClosed: Boolean get() = roomSolution != null

    /**
     * True when the reticle is close enough to the first corner that tapping would close
     * the loop rather than add another corner.
     */
    var isNearStartCorner by mutableStateOf(false)
        private set

    /**
     * True in the band around the first corner where a tap is *not* taken as a close.
     *
     * Worth showing, because this is exactly where the interface has to be explicit: the
     * user is back near where they started, so they need to know whether the next tap
     * adds a corner or finishes the room.
     */
    var isApproachingStart by mutableStateOf(false)
        private set

    private var nextSegmentId = 1L

    init {
        viewModelScope.launch {
            controller.outcomes.collect(::onCaptureOutcome)
        }
        viewModelScope.launch {
            controller.walls.collect(::onWallOutcome)
        }
        pushScene()
    }

    // --- user actions ---------------------------------------------------------------

    fun capture() = controller.requestCapture()

    fun selectCaptureMode(next: CaptureMode) {
        if (next == captureMode) return
        captureMode = next
        notice = CaptureNotice.Advice(
            when (next) {
                CaptureMode.DISTANCE -> "Tap two points to measure between them"
                CaptureMode.ROOM -> "Tap each corner of the room in order, walking round"
            },
        )
        pushScene()
    }

    /**
     * Switch between tapping corners and taking walls.
     *
     * Refused mid-room rather than silently converting: the two produce corner lists with
     * different provenance, and half a room of each would be a plan whose accuracy nobody
     * could describe.
     */
    fun selectCornerMethod(next: CornerMethod) {
        if (next == cornerMethod) return
        if (roomCorners.isNotEmpty() || capturedWalls.isNotEmpty() || isRoomClosed) {
            notice = CaptureNotice.Warning("Finish or clear this room before changing method")
            return
        }
        cornerMethod = next
        notice = CaptureNotice.Advice(next.hint)
        pushScene()
    }

    /** Take the wall under the reticle — docs/ACCURACY.md M10. */
    fun captureWall() = controller.requestWall()

    /**
     * Close the perimeter without re-measuring the first corner.
     *
     * This costs accuracy and the user should know it: without a second reading of the
     * starting corner there is no measured drift to distribute, so loop closure
     * (docs/ACCURACY.md M7) has nothing to work with and the plan is only as good as the
     * raw observations. Walking back to the first corner is always the better option,
     * which is why it is the one the interface nudges towards.
     */
    fun closeRoom() {
        if (isWallMode) closeWallRoom() else solveRoom(closingObservation = null)
    }

    /**
     * Start a fresh room, keeping the one just finished.
     *
     * Distinct from [undo]: a completed room is a legitimate part of the project and
     * several of them in one plan is the normal multi-room case. Only the id is dropped,
     * so a later undo cannot delete a room the user has moved on from.
     */
    fun restartRoom() {
        detectedCeilingHeight = null
        roomCorners.clear()
        capturedWalls.clear()
        roomSolution = null
        savedRoomId = null
        isNearStartCorner = false
        isApproachingStart = false
        notice = null
        pushScene()
    }

    private fun discardSavedRoom() {
        val id = savedRoomId ?: return
        savedRoomId = null
        viewModelScope.launch {
            runCatching { repository.deleteRoom(id) }
        }
    }

    /** Tracks whether the next tap would close the loop, so the interface can say so. */
    fun updateStartProximity(reticle: com.measure.core.geometry.Vec3?) {
        val intent = if (isRoomClosed || isWallMode) {
            ClosingIntent.ADD_CORNER
        } else {
            LoopClosure.classify(roomCorners.size, distanceToStart(reticle))
        }
        isNearStartCorner = intent == ClosingIntent.CLOSE_LOOP
        isApproachingStart = intent == ClosingIntent.APPROACHING_START
    }

    private fun distanceToStart(point: com.measure.core.geometry.Vec3?): Double? {
        val start = roomCorners.firstOrNull()?.position ?: return null
        return point?.let { start.horizontalDistanceTo(it) }
    }

    /**
     * The pending point is kept when the mode changes. Placing a point on the floor and
     * *then* switching to plumb is a natural way to ask for a ceiling height, and
     * throwing the point away would punish it.
     */
    fun selectMode(next: MeasurementMode) {
        if (next == mode) return
        mode = next
        notice = CaptureNotice.Advice(next.hint)
        pushScene()
    }

    fun toggleUnits() {
        unitSystem = if (unitSystem == UnitSystem.METRIC) UnitSystem.IMPERIAL else UnitSystem.METRIC
    }

    fun setTorch(on: Boolean) = controller.setTorch(on)

    fun togglePlanes() {
        showPlanes = !showPlanes
        pushScene()
    }

    /** Undo the half-finished measurement first, then the last completed one. */
    fun undo() {
        when (captureMode) {
            CaptureMode.ROOM -> {
                // Undoing a closed room reopens it rather than deleting a corner: the
                // solved result is the thing most likely to be wrong, and losing a
                // corner as well would punish a user who just wanted another look.
                //
                // Autosave means the closed room is already a row in the database, so
                // reopening it has to take that row back. Without this, closing and
                // undoing repeatedly left a stack of abandoned attempts in the project,
                // all drawn on top of each other in the list thumbnail.
                if (roomSolution != null) {
                    roomSolution = null
                    discardSavedRoom()
                } else if (isWallMode) {
                    if (capturedWalls.isNotEmpty()) capturedWalls.removeAt(capturedWalls.lastIndex)
                } else if (roomCorners.isNotEmpty()) {
                    roomCorners.removeAt(roomCorners.lastIndex)
                }
            }

            CaptureMode.DISTANCE -> {
                if (pending != null) {
                    pending = null
                } else if (segments.isNotEmpty()) {
                    segments.removeAt(segments.lastIndex)
                }
            }
        }
        notice = null
        pushScene()
    }

    fun clear() {
        pending = null
        segments.clear()
        discardSavedRoom()
        restartRoom()
    }

    /** True when there is enough taken to attempt a close. */
    val canCloseRoom: Boolean
        get() = if (isWallMode) {
            capturedWalls.size >= WallChain.MINIMUM_WALLS_FOR_ROOM
        } else {
            roomCorners.size >= LoopClosure.MINIMUM_CORNERS
        }

    /** True when undo has something to take back. */
    val canUndoRoom: Boolean
        get() = isRoomClosed || roomCorners.isNotEmpty() || capturedWalls.isNotEmpty()

    fun dismissNotice() {
        notice = null
    }

    // --- formatting -----------------------------------------------------------------

    /** Every displayed measurement carries its tolerance — docs/ACCURACY.md M12. */
    fun format(segment: MeasuredSegment): String =
        LengthFormatter.formatWithUncertainty(segment.length, segment.sigma, unitSystem)

    fun formatArea(solution: RoomSolution): String =
        com.measure.core.units.AreaFormatter.format(solution.area, unitSystem)

    fun formatLength(metres: Double): String =
        LengthFormatter.format(com.measure.core.units.Length(metres), unitSystem)

    // --- capture pipeline -----------------------------------------------------------

    private fun onCaptureOutcome(outcome: CaptureOutcome) {
        when (outcome) {
            is CaptureOutcome.Rejected -> {
                notice = CaptureNotice.Warning(outcome.reason.message)
            }

            is CaptureOutcome.Accepted -> when (captureMode) {
                CaptureMode.ROOM -> addCorner(outcome.point)
                CaptureMode.DISTANCE -> {
                    val anchor = pending
                    if (anchor == null) {
                        pending = outcome.point
                        notice = CaptureNotice.Advice("Now aim at the other end")
                    } else {
                        completeSegment(anchor, outcome.point)
                    }
                }
            }
        }
        pushScene()
    }

    /**
     * A corner, or the closing re-observation of the first one.
     *
     * Landing back on the starting corner is how a capture ends, and it is worth more
     * than a button press: the gap between the two readings of that corner *is* the
     * accumulated drift, measured directly, which is exactly what the compass-rule
     * adjustment needs (docs/ACCURACY.md M7).
     */
    private fun addCorner(point: SampledPoint) {
        if (isRoomClosed) return

        val intent = LoopClosure.classify(roomCorners.size, distanceToStart(point.position))
        if (intent == ClosingIntent.CLOSE_LOOP) {
            solveRoom(closingObservation = point)
            return
        }

        roomCorners += point
        notice = when {
            // Near the start but not on it. This used to close the room, and half a metre
            // of slack is wide enough to swallow a real corner — an alcove, a chimney
            // breast, the corner of a fitted unit next to the doorway you began at. The
            // corner is taken, and the way to finish is the button that says so.
            intent == ClosingIntent.APPROACHING_START ->
                CaptureNotice.Advice("Corner added — tap Close to finish, or the first corner itself")

            roomCorners.size == 1 -> CaptureNotice.Advice("Walk to the next corner and tap again")
            roomCorners.size == LoopClosure.MINIMUM_CORNERS ->
                CaptureNotice.Advice("Keep going, then return to the first corner to close")

            else -> null
        }
    }

    // --- wall-face capture ------------------------------------------------------------

    private fun onWallOutcome(outcome: WallCaptureOutcome) {
        when (outcome) {
            is WallCaptureOutcome.Rejected -> notice = CaptureNotice.Warning(outcome.reason.message)
            is WallCaptureOutcome.Accepted -> addWall(outcome.face)
        }
        pushScene()
    }

    /**
     * Takes a wall, provided it makes a corner with the one before it.
     *
     * Checked here and not only when the room closes, because the moment of the mistake
     * is the only moment the user can fix it — they are standing in front of both walls.
     * Discovering four walls later that two of them never met would mean starting again.
     */
    private fun addWall(face: WallFace) {
        if (isRoomClosed) return

        val previous = capturedWalls.lastOrNull()
        capturedWalls += face

        if (previous != null) {
            val problem = WallChain.corners(capturedWalls, closed = false)
                .unresolved[capturedWalls.lastIndex - 1]
            if (problem != null) {
                capturedWalls.removeAt(capturedWalls.lastIndex)
                notice = CaptureNotice.Warning(
                    when (problem) {
                        WallPairProblem.SAME_WALL -> "That is the wall you just took — turn to the next one"
                        WallPairProblem.TOO_SHALLOW ->
                            "Those two walls are too nearly parallel to find a corner between them"
                    },
                )
                return
            }
        }

        notice = when (capturedWalls.size) {
            1 -> CaptureNotice.Advice("Now turn to the next wall")
            WallChain.MINIMUM_WALLS_FOR_ROOM ->
                CaptureNotice.Advice("Keep going, then tap Close when the last wall is taken")
            else -> null
        }
    }

    /** Corners derived from the walls taken so far. Each pair of walls gives one. */
    fun wallCorners(closed: Boolean = isRoomClosed): List<Vec2> =
        WallChain.corners(capturedWalls, closed).corners.map { it.position }

    /**
     * Close a wall-face room.
     *
     * The wrap-around pair — the last wall against the first — is what shuts the loop,
     * and it does so geometrically rather than by re-observation. That means there is no
     * misclosure to distribute and none to report: the honest figure for this capture is
     * the worst corner's own tolerance, which is what the readout shows instead.
     */
    private fun closeWallRoom() {
        if (capturedWalls.size < WallChain.MINIMUM_WALLS_FOR_ROOM) {
            notice = CaptureNotice.Warning("A room needs at least three walls")
            return
        }

        val chain = WallChain.corners(capturedWalls, closed = true)
        if (!chain.isComplete) {
            notice = CaptureNotice.Warning(
                "The last wall does not meet the first — take the wall that closes the room",
            )
            return
        }

        val corners = chain.corners.map { CapturedCorner(it.position, it.sigma) }
        finishRoom(
            capture = RoomCapture(corners = corners),
            measured = chain.corners.map { it.position },
            sigmas = chain.corners.map { it.sigma },
        ) {
            val worst = chain.worstSigma
            CaptureNotice.Advice(
                if (worst == null) "Room closed" else "Room closed — corners to ±${formatLength(worst)}",
            )
        }
    }

    // --- solving ------------------------------------------------------------------------

    private fun solveRoom(closingObservation: SampledPoint?) {
        if (roomCorners.size < LoopClosure.MINIMUM_CORNERS) {
            notice = CaptureNotice.Warning("A room needs at least three corners")
            return
        }

        val measured = roomCorners.map { it.position.toFloorPlane() }
        val sigmas = roomCorners.map { it.sigma }

        finishRoom(
            capture = RoomCapture(
                corners = measured.mapIndexed { index, position -> CapturedCorner(position, sigmas[index]) },
                closingObservation = closingObservation?.position?.toFloorPlane(),
            ),
            measured = measured,
            sigmas = sigmas,
        ) { solution ->
            // A large misclosure means something went wrong during the walk, and quietly
            // smearing it away would be dishonest. Say so and let the user decide.
            when {
                solution.closure.wasAdjusted && !solution.closure.isAcceptable -> CaptureNotice.Warning(
                    "Closed with ${percent(solution.closure.relativeError)} drift — consider re-measuring",
                )

                // Shutting the loop with the button rather than by re-reading the first
                // corner leaves nothing to check the walk against, and reporting "0.0%
                // drift" for that would be claiming a measurement never taken.
                !solution.closure.wasAdjusted ->
                    CaptureNotice.Advice("Room closed — no second reading, so drift is unmeasured")

                else -> CaptureNotice.Advice("Room closed")
            }
        }
    }

    private fun finishRoom(
        capture: RoomCapture,
        measured: List<Vec2>,
        sigmas: List<Double>,
        describe: (RoomSolution) -> CaptureNotice,
    ) {
        val solution = runCatching { RoomSolver.solve(capture) }.getOrElse {
            notice = CaptureNotice.Warning("Could not solve this room — try re-measuring")
            return
        }

        roomSolution = solution
        isNearStartCorner = false
        isApproachingStart = false
        autosave {
            savedRoomId = repository.saveRoom(
                projectId = it,
                name = repository.nextRoomName(it),
                solution = solution,
                measured = measured,
                sigmas = sigmas,
                ceilingHeight = detectedCeilingHeight,
            )
        }
        notice = describe(solution)
    }

    /** Plan-view corners for the minimap: solved if we have a solution, raw if not. */
    fun planOutline(): List<Vec2> = roomSolution?.polygon?.vertices
        ?: if (isWallMode) wallCorners(closed = false) else roomCorners.map { it.position.toFloorPlane() }

    fun percent(fraction: Double): String =
        String.format(java.util.Locale.getDefault(), "%.1f%%", fraction * 100)

    private fun completeSegment(anchor: SampledPoint, second: SampledPoint) {
        val constrained = mode.constrain(anchor.position, second.position)
        val segment = MeasuredSegment(
            id = nextSegmentId++,
            from = anchor,
            to = second.copy(position = constrained.position),
            mode = mode,
            correction = constrained.correction,
        )
        segments += segment
        pending = null
        autosave { repository.saveMeasurement(it, segment) }

        // A large constraint correction means the user aimed somewhere this mode does
        // not permit. Saying so is the honest alternative to silently snapping it.
        notice = if (constrained.isNotable) {
            CaptureNotice.Warning(
                "Moved ${formatLength(constrained.correction)} to keep it ${mode.label.lowercase()}",
            )
        } else {
            null
        }
    }

    /**
     * Writes immediately, on the first save creating the project to write into.
     *
     * There is no save button and there should not be one: a room that took a minute of
     * walking to capture must survive the phone ringing, and the only way to guarantee
     * that is to have already written it. Failures are surfaced rather than swallowed —
     * silently losing work is worse than saying so.
     */
    private fun autosave(write: suspend (projectId: Long) -> Unit) {
        viewModelScope.launch {
            try {
                val id = projectId ?: repository.createDefaultProject(unitSystem).also { projectId = it }
                write(id)
            } catch (error: Throwable) {
                notice = CaptureNotice.Warning("Could not save — ${error.javaClass.simpleName}")
            }
        }
    }

    private fun pushScene() {
        // Wall mode has no tapped corners to draw, so the renderer is given the derived
        // ones instead. They are laid on the floor because that is where the walls meet
        // it, and because every other piece of room drawing already lives there.
        val floorHeight = controller.state.value.floor?.height ?: 0.0
        val corners = if (isWallMode) {
            wallCorners().map { com.measure.core.geometry.Vec3(it.x, floorHeight, -it.y) }
        } else {
            roomCorners.map { it.position }
        }

        controller.updateScene(
            ArScene(
                captureMode = captureMode,
                segments = segments.map { ArSegment(it.id, it.from.position, it.to.position) },
                pendingAnchor = pending?.position,
                mode = mode,
                roomCorners = corners,
                cornerMethod = cornerMethod,
                takenWalls = capturedWalls.toList(),
                roomClosed = isRoomClosed,
                showPlanes = showPlanes,
            ),
        )
    }

    override fun onCleared() {
        super.onCleared()
        controller.close()
    }
}
