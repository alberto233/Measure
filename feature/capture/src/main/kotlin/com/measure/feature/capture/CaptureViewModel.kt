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
import com.measure.core.geometry.CapturedCorner
import com.measure.core.geometry.RoomCapture
import com.measure.core.geometry.RoomSolution
import com.measure.core.geometry.RoomSolver
import com.measure.core.geometry.Vec2
import com.measure.core.geometry.capture.CaptureOutcome
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

    var captureMode by mutableStateOf(CaptureMode.DISTANCE)
        private set

    /** Corners in walk order, each already projected onto the floor plane by `:ar`. */
    val roomCorners = mutableStateListOf<SampledPoint>()

    /** Non-null once the perimeter is closed and the correction pipeline has run. */
    var roomSolution by mutableStateOf<RoomSolution?>(null)
        private set

    val isRoomClosed: Boolean get() = roomSolution != null

    /**
     * True when the reticle is close enough to the first corner that tapping would close
     * the loop rather than add another corner.
     */
    var isNearStartCorner by mutableStateOf(false)
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

    fun restartRoom() {
        roomCorners.clear()
        roomSolution = null
        isNearStartCorner = false
        notice = null
        pushScene()
    }

    /** Tracks whether the next tap would close the loop, so the interface can say so. */
    fun updateStartProximity(reticle: com.measure.core.geometry.Vec3?) {
        val start = roomCorners.firstOrNull()?.position
        isNearStartCorner = start != null &&
            reticle != null &&
            !isRoomClosed &&
            roomCorners.size >= MINIMUM_CORNERS &&
            start.horizontalDistanceTo(reticle) <= CLOSING_RADIUS_METRES
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
                if (roomSolution != null) {
                    roomSolution = null
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
        restartRoom()
    }

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

        val start = roomCorners.firstOrNull()
        val closesTheLoop = start != null &&
            roomCorners.size >= MINIMUM_CORNERS &&
            start.position.horizontalDistanceTo(point.position) <= CLOSING_RADIUS_METRES

        if (closesTheLoop) {
            solveRoom(closingObservation = point)
        } else {
            roomCorners += point
            notice = when (roomCorners.size) {
                1 -> CaptureNotice.Advice("Walk to the next corner and tap again")
                MINIMUM_CORNERS -> CaptureNotice.Advice("Keep going, then return to the first corner to close")
                else -> null
            }
        }
    }

    private fun solveRoom(closingObservation: SampledPoint?) {
        if (roomCorners.size < MINIMUM_CORNERS) {
            notice = CaptureNotice.Warning("A room needs at least three corners")
            return
        }

        val solution = runCatching {
            RoomSolver.solve(
                RoomCapture(
                    corners = roomCorners.map { CapturedCorner(it.position.toFloorPlane(), it.sigma) },
                    closingObservation = closingObservation?.position?.toFloorPlane(),
                ),
            )
        }.getOrElse {
            notice = CaptureNotice.Warning("Could not solve this room — try re-measuring")
            return
        }

        roomSolution = solution
        isNearStartCorner = false

        // A large misclosure means something went wrong during the walk, and quietly
        // smearing it away would be dishonest. Say so and let the user decide.
        notice = if (solution.closure.wasAdjusted && !solution.closure.isAcceptable) {
            CaptureNotice.Warning(
                "Closed with ${percent(solution.closure.relativeError)} drift — consider re-measuring",
            )
        } else {
            CaptureNotice.Advice("Room closed")
        }
    }

    /** Plan-view corners for the minimap: solved if we have a solution, raw if not. */
    fun planOutline(): List<Vec2> =
        roomSolution?.polygon?.vertices ?: roomCorners.map { it.position.toFloorPlane() }

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

    private fun pushScene() {
        controller.updateScene(
            ArScene(
                captureMode = captureMode,
                segments = segments.map { ArSegment(it.id, it.from.position, it.to.position) },
                pendingAnchor = pending?.position,
                mode = mode,
                roomCorners = roomCorners.map { it.position },
                roomClosed = isRoomClosed,
                showPlanes = showPlanes,
            ),
        )
    }

    private companion object {
        /** A polygon needs three corners before it encloses anything. */
        const val MINIMUM_CORNERS = 3

        /** Tap within this of the first corner and the loop closes instead of growing. */
        const val CLOSING_RADIUS_METRES = 0.45
    }

    override fun onCleared() {
        super.onCleared()
        controller.close()
    }
}
