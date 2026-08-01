package com.measure.ar

import com.measure.core.geometry.Vec3
import com.measure.core.geometry.capture.HitSource
import com.measure.core.geometry.capture.MeasurementMode
import com.measure.core.geometry.capture.RangeAdvice
import com.measure.core.geometry.capture.SamplingConfig
import com.measure.core.geometry.capture.TrackingStatus

/**
 * Where the session is in its life. Distinguishing these matters because three of them
 * are the user's to fix and the app has to say which.
 */
enum class ArPhase {
    STARTING,
    NEEDS_CAMERA_PERMISSION,
    NEEDS_ARCORE_INSTALL,
    RUNNING,
    PAUSED,
    FAILED,
}

/** A session failure worth putting on screen, with the retry affordance if there is one. */
data class ArFailure(
    val message: String,
    val detail: String? = null,
    val recoverable: Boolean = true,
)

/** Where the reticle is currently pointing, if anywhere. */
data class ReticleTarget(
    val position: Vec3,
    val range: Double,
    val source: HitSource,
)

/** Progress of an in-flight multi-frame sample burst, for the capture button animation. */
data class SamplingProgress(val collected: Int, val target: Int) {
    val fraction: Float get() = if (target <= 0) 0f else (collected.toFloat() / target).coerceIn(0f, 1f)
}

/** The live, not-yet-committed measurement between the placed point and the reticle. */
data class MeasurementPreview(
    val lengthMetres: Double,
    val correction: Double,
    val correctionIsNotable: Boolean,
)

/**
 * A world point projected to screen coordinates by the render thread.
 *
 * Labels are drawn in Compose rather than as textured quads in GL — text rendering in
 * OpenGL is a font atlas and a lot of code for something the UI toolkit already does
 * beautifully. The render thread already holds the view-projection matrix, so it does the
 * projection and the overlay just places a composable at the resulting pixel.
 */
data class ScreenAnchor(val id: Long, val x: Float, val y: Float)

/**
 * Everything the capture UI needs from the AR session, published once per frame.
 *
 * Continuous values are quantised before publication so that a stationary phone produces
 * an unchanging state and Compose stops recomposing. Without that, a 60 fps session
 * recomposes the whole overlay 60 times a second forever.
 */
data class ArUiState(
    val phase: ArPhase = ArPhase.STARTING,
    val tracking: TrackingStatus = TrackingStatus(),
    val target: ReticleTarget? = null,
    val rangeAdvice: RangeAdvice = RangeAdvice.IDEAL,
    val depthEnabled: Boolean = false,
    val sampling: SamplingProgress? = null,
    val preview: MeasurementPreview? = null,
    val anchors: List<ScreenAnchor> = emptyList(),
    val failure: ArFailure? = null,
) {
    /** True when a tap should be allowed to start a sample burst. */
    val canCapture: Boolean
        get() = phase == ArPhase.RUNNING &&
            tracking.canCapture &&
            target != null &&
            rangeAdvice != RangeAdvice.TOO_CLOSE &&
            sampling == null
}

/** One committed measurement, in the form the renderer needs. */
data class ArSegment(val id: Long, val from: Vec3, val to: Vec3)

/**
 * What the renderer should draw, pushed from the capture view model.
 *
 * The live preview is deliberately *not* pushed as geometry. The renderer is given the
 * anchor and the mode and computes the moving end from its own reticle hit each frame,
 * so the rubber-band line tracks at frame rate rather than at whatever rate state
 * crosses the thread boundary.
 */
data class ArScene(
    val segments: List<ArSegment> = emptyList(),
    val pendingAnchor: Vec3? = null,
    val mode: MeasurementMode = MeasurementMode.FREE,
    val showPlanes: Boolean = true,
    val samplingConfig: SamplingConfig = SamplingConfig(),
) {
    companion object {
        /** Anchor id for the live preview label; real segment ids start at 1. */
        const val PREVIEW_ANCHOR_ID = -1L
    }
}
