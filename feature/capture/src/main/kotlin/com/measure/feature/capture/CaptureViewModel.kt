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
import com.measure.ar.MeasureArController
import com.measure.core.data.MeasureData
import com.measure.core.data.MeasureRepository
import com.measure.core.geometry.CapturedCorner
import com.measure.core.geometry.RoomCapture
import com.measure.core.geometry.RoomSolution
import com.measure.core.geometry.RoomSolver
import com.measure.core.geometry.Vec2
import com.measure.core.geometry.Vec3
import com.measure.core.geometry.capture.CaptureOutcome
import com.measure.core.geometry.capture.ClosingIntent
import com.measure.core.geometry.capture.CornerSnapper
import com.measure.core.geometry.capture.LoopClosure
import com.measure.core.geometry.capture.MeasuredSegment
import com.measure.core.geometry.capture.MeasurementMode
import com.measure.core.geometry.capture.SampledPoint
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

    /**
     * The capture-time rectilinear assist — docs/ACCURACY.md M6.
     *
     * **On by default**, which is a deliberate choice and not an oversight. A feature
     * defaulted off is a feature nobody field-tests, and this one is only worth keeping if
     * it makes a real capture easier; that cannot be learned from a setting nobody finds.
     * It stays a toggle because a room with a genuine bay is a room where the user knows
     * better than the prior does.
     */
    var snapEnabled by mutableStateOf(true)
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

    /**
     * Corners in walk order, each already projected onto the floor plane by `:ar`.
     *
     * **These are the observations, and nothing ever modifies them.** They are what the
     * camera reported and what gets written to `measuredX/measuredY`, so that a re-solve
     * is idempotent and the rectilinear assist can be switched off — or reverted out of
     * the app entirely — without any room captured while it was on being stuck with it.
     */
    val roomCorners = mutableStateListOf<RoomCorner>()

    /**
     * A captured corner, together with how it was captured.
     *
     * The flag lives beside the sample rather than in a list of its own. Two lists kept in
     * step is two lists that can fall out of step, and here the failure would be silent —
     * an undo popping one and not the other would shift every later corner's flag by one,
     * squaring corners the user had asked to be left alone.
     */
    data class RoomCorner(
        /** What the camera reported. Never modified — this is the observation. */
        val sample: SampledPoint,
        /** Whether the rectilinear assist was switched on when this corner was taken. */
        val assisted: Boolean,
    )

    /**
     * The same corners with the assist applied, which is what gets drawn and solved.
     *
     * Derived on read rather than stored alongside the observations. Two lists kept in
     * step is two lists that can fall out of step, and the failure would be silent: a plan
     * showing one room while the database held another.
     */
    val snappedCorners: List<Vec2>
        get() = snapper.snapChain(
            observed = roomCorners.map { it.sample.position.toFloorPlane() },
            assisted = roomCorners.map { it.assisted },
        )

    private val snapper = CornerSnapper()

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
     * Close the perimeter without re-measuring the first corner.
     *
     * This costs accuracy and the user should know it: without a second reading of the
     * starting corner there is no measured drift to distribute, so loop closure
     * (docs/ACCURACY.md M7) has nothing to work with and the plan is only as good as the
     * raw observations. Walking back to the first corner is always the better option,
     * which is why it is the one the interface nudges towards.
     */
    fun closeRoom() = solveRoom(closingObservation = null)

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
        val intent = if (isRoomClosed) {
            ClosingIntent.ADD_CORNER
        } else {
            LoopClosure.classify(roomCorners.size, distanceToStart(reticle))
        }
        isNearStartCorner = intent == ClosingIntent.CLOSE_LOOP
        isApproachingStart = intent == ClosingIntent.APPROACHING_START
    }

    private fun distanceToStart(point: com.measure.core.geometry.Vec3?): Double? {
        val start = roomCorners.firstOrNull()?.sample?.position ?: return null
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


    fun togglePlanes() {
        showPlanes = !showPlanes
        pushScene()
    }

    /**
     * Applies to corners captured **from now on**, and never reaches backwards.
     *
     * The first version re-derived the whole room from the toggle's current value, which a
     * field test caught immediately: turning the assist off to capture a bay and back on
     * for the next wall straightened the bay retroactively. That destroys the one thing the
     * toggle exists to let somebody say.
     *
     * A room that is half squared and half not is therefore a state that does exist, and it
     * is the correct one — it is a record of what the user asked for at each corner.
     */
    fun toggleSnap() {
        snapEnabled = !snapEnabled
        notice = CaptureNotice.Advice(
            if (snapEnabled) {
                "Square corners on — from the next corner"
            } else {
                "Square corners off — corners land where you aim"
            },
        )
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

    /** True when there is enough of a perimeter to attempt a close. */
    val canCloseRoom: Boolean get() = roomCorners.size >= LoopClosure.MINIMUM_CORNERS

    /** True when undo has something to take back. */
    val canUndoRoom: Boolean get() = isRoomClosed || roomCorners.isNotEmpty()

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

        // The toggle's value is recorded here and never re-read. Deriving it later from
        // whatever the toggle happens to say would mean flipping it back on retroactively
        // squares a bay somebody deliberately captured with it off.
        roomCorners += RoomCorner(point, assisted = snapEnabled)
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

    // --- solving ------------------------------------------------------------------------

    private fun solveRoom(closingObservation: SampledPoint?) {
        if (roomCorners.size < LoopClosure.MINIMUM_CORNERS) {
            notice = CaptureNotice.Warning("A room needs at least three corners")
            return
        }

        val measured = roomCorners.map { it.sample.position.toFloorPlane() }
        val sigmas = roomCorners.map { it.sample.sigma }
        // What the solver starts from: the walk as the user watched it square up. The
        // observations go through untouched as `measured`, which is what the repository
        // stores and what every later re-solve begins from.
        val solving = snappedCorners

        finishRoom(
            capture = RoomCapture(
                corners = solving.mapIndexed { index, position -> CapturedCorner(position, sigmas[index]) },
                // Not snapped. The closing reading's whole value is that the gap between it
                // and the first corner *is* the accumulated drift, measured directly
                // (docs/ACCURACY.md M7). Squaring it would adjust away the very quantity
                // the compass rule needs.
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
                // Which ARCore frame these corners are in. Rooms captured without leaving
                // this screen share it and are positioned correctly relative to each
                // other; a later visit starts a new session with a new origin, and the
                // repository has to know that before it draws them on one plan.
                captureSession = controller.worldFrame,
            )
        }
        notice = describe(solution)
    }

    /** Plan-view corners for the minimap: solved if we have a solution, raw if not. */
    fun planOutline(): List<Vec2> =
        roomSolution?.polygon?.vertices ?: snappedCorners

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
        controller.updateScene(
            ArScene(
                captureMode = captureMode,
                segments = segments.map { ArSegment(it.id, it.from.position, it.to.position) },
                pendingAnchor = pending?.position,
                mode = mode,
                // The squared chain, so the live plan and the minimap show the room that
                // will actually be saved. Lifted back to each corner's own height.
                roomCorners = snappedCorners.mapIndexed { index, flat ->
                    Vec3(flat.x, roomCorners[index].sample.position.y, -flat.y)
                },
                snapEnabled = snapEnabled,
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
