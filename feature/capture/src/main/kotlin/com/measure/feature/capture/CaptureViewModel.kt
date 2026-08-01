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
import com.measure.ar.MeasureArController
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

    private var nextSegmentId = 1L

    init {
        viewModelScope.launch {
            controller.outcomes.collect(::onCaptureOutcome)
        }
        pushScene()
    }

    // --- user actions ---------------------------------------------------------------

    fun capture() = controller.requestCapture()

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

    /** Undo the half-finished measurement first, then the last completed one. */
    fun undo() {
        if (pending != null) {
            pending = null
        } else if (segments.isNotEmpty()) {
            segments.removeAt(segments.lastIndex)
        }
        notice = null
        pushScene()
    }

    fun clear() {
        pending = null
        segments.clear()
        notice = null
        pushScene()
    }

    fun dismissNotice() {
        notice = null
    }

    // --- formatting -----------------------------------------------------------------

    /** Every displayed measurement carries its tolerance — docs/ACCURACY.md M12. */
    fun format(segment: MeasuredSegment): String =
        LengthFormatter.formatWithUncertainty(segment.length, segment.sigma, unitSystem)

    fun formatLength(metres: Double): String =
        LengthFormatter.format(com.measure.core.units.Length(metres), unitSystem)

    // --- capture pipeline -----------------------------------------------------------

    private fun onCaptureOutcome(outcome: CaptureOutcome) {
        when (outcome) {
            is CaptureOutcome.Rejected -> {
                notice = CaptureNotice.Warning(outcome.reason.message)
            }

            is CaptureOutcome.Accepted -> {
                val anchor = pending
                if (anchor == null) {
                    pending = outcome.point
                    notice = CaptureNotice.Advice("Now aim at the other end")
                } else {
                    completeSegment(anchor, outcome.point)
                }
            }
        }
        pushScene()
    }

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
                segments = segments.map { ArSegment(it.id, it.from.position, it.to.position) },
                pendingAnchor = pending?.position,
                mode = mode,
                showPlanes = showPlanes,
            ),
        )
    }

    override fun onCleared() {
        super.onCleared()
        controller.close()
    }
}
