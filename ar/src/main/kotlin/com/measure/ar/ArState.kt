package com.measure.ar

import com.measure.core.geometry.Vec3
import com.measure.core.geometry.capture.HorizontalSurface
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

/** Which kind of capture the screen is doing. */
enum class CaptureMode { DISTANCE, ROOM }

/**
 * How a room's corners are obtained — docs/ACCURACY.md M10.
 *
 * Two genuinely different capture actions, not two renderings of one. Tapping is faster
 * and needs the junction to be visible; taking walls needs neither, which is what makes
 * it the answer in an occupied room where the corner is behind the furniture.
 */
enum class CornerMethod(val label: String, val hint: String) {
    TAP_FLOOR("Corners", "Tap each corner of the room in order"),
    WALL_FACES("Walls", "Point at each wall in turn — the corners work themselves out"),
}

/** The wall under the reticle, reduced to what the interface has to say about it. */
data class AimedWallState(
    val id: Long,
    /** How much of the wall ARCore has fitted, in metres. */
    val extent: Double,
    val range: Double,
    /** True when this is a wall already taken for this room. */
    val alreadyTaken: Boolean,
)

/**
 * The dominant floor, once one has been found — docs/ACCURACY.md M2.
 *
 * Room corners are projected onto this single height, which removes all vertical jitter
 * from the plan, guarantees the polygon is genuinely planar so its area is well defined,
 * and turns a 3D estimation problem into the 2D one the constraint solver expects.
 */
data class FloorState(
    val height: Double,
    val area: Double,
    val planeCount: Int,
) {
    val isEstablished: Boolean get() = area >= com.measure.core.geometry.capture.FloorSelector.ESTABLISHED_AREA

    companion object {
        fun from(surface: HorizontalSurface) =
            FloorState(surface.height, surface.area, surface.planeCount)
    }
}

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
    /** Whether the camera config ARCore chose allows the torch at all. */
    val torchSupported: Boolean = false,
    val torchOn: Boolean = false,
    val sampling: SamplingProgress? = null,
    val preview: MeasurementPreview? = null,
    val anchors: List<ScreenAnchor> = emptyList(),
    val floor: FloorState? = null,
    /** Room capture, aiming at something that is not the floor. Nothing can be placed. */
    val offFloor: Boolean = false,
    /** Ceiling height above the floor, if a ceiling has come into view. */
    val ceilingHeight: Double? = null,
    /** Wall-face capture: the wall being pointed at, if any. */
    val aimedWall: AimedWallState? = null,
    val failure: ArFailure? = null,
) {
    /** True when a tap should be allowed to start a sample burst. */
    val canCapture: Boolean
        get() = phase == ArPhase.RUNNING &&
            tracking.canCapture &&
            target != null &&
            rangeAdvice != RangeAdvice.TOO_CLOSE &&
            sampling == null

    /**
     * Room capture additionally needs a floor to project onto, so it is gated harder
     * than a one-off distance. Refusing until the floor is established is the difference
     * between a plan and a scatter of points at slightly different heights.
     */
    val canCaptureCorner: Boolean
        get() = canCapture && floor?.isEstablished == true

    /**
     * Taking a wall needs no floor and no reticle hit — only a wall.
     *
     * That is the entire point. A corner tap needs the floor established so the point has
     * somewhere to land; a wall is its own reference, which is why this works in a room
     * where the floor is covered.
     */
    val canTakeWall: Boolean
        get() = phase == ArPhase.RUNNING &&
            tracking.canCapture &&
            aimedWall != null &&
            !aimedWall.alreadyTaken
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
    val captureMode: CaptureMode = CaptureMode.ROOM,
    val segments: List<ArSegment> = emptyList(),
    val pendingAnchor: Vec3? = null,
    val mode: MeasurementMode = MeasurementMode.FREE,
    /** Room corners in order, already projected onto the floor plane. */
    val roomCorners: List<Vec3> = emptyList(),
    val cornerMethod: CornerMethod = CornerMethod.TAP_FLOOR,
    /** Wall-face capture: the planes already taken, so the renderer can mark them. */
    val takenWallIds: Set<Long> = emptySet(),
    /** True once the perimeter has been closed and there is nothing left to add. */
    val roomClosed: Boolean = false,
    val showPlanes: Boolean = true,
    val samplingConfig: SamplingConfig = SamplingConfig(),
) {
    companion object {
        /** Anchor id for the live preview label; real segment ids start at 1. */
        const val PREVIEW_ANCHOR_ID = -1L
    }
}
