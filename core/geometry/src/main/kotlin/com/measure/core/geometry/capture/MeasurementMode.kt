package com.measure.core.geometry.capture

import com.measure.core.geometry.Vec3
import kotlin.math.abs

/**
 * How the second point of a measurement is constrained relative to the first.
 *
 * These are not display filters; they change the recorded geometry. The reason they earn
 * their place is that they replace an axis the user cannot aim accurately with one the
 * device knows precisely. ARCore's gravity vector is good to a fraction of a degree
 * because it comes from the accelerometer, whereas a human holding a phone at arm's
 * length cannot keep a reticle level to better than a couple of degrees. Constraining to
 * gravity therefore removes error rather than merely tidying the number.
 */
enum class MeasurementMode {
    /** Straight line between two points, however they lie. */
    FREE,

    /**
     * Level: the second point is forced to the first point's height. This is the mode
     * for a room width across furniture, or an alcove where floor and ceiling are not
     * both reachable.
     */
    HORIZONTAL,

    /**
     * Plumb: the second point is forced directly above or below the first. This is how
     * ceiling height and wall height get measured, and it is the case a tape measure
     * genuinely struggles with.
     */
    VERTICAL,
    ;

    /**
     * Apply the constraint, returning the adjusted point and how far it moved.
     *
     * The correction is surfaced to the user. A large one means they were aiming
     * somewhere the mode does not permit, and silently snapping a 40 cm error would be
     * the same dishonesty this app is built to avoid.
     */
    fun constrain(anchor: Vec3, candidate: Vec3): ConstrainedPoint = when (this) {
        FREE -> ConstrainedPoint(candidate, 0.0)

        HORIZONTAL -> ConstrainedPoint(
            position = Vec3(candidate.x, anchor.y, candidate.z),
            correction = abs(candidate.y - anchor.y),
        )

        VERTICAL -> ConstrainedPoint(
            position = Vec3(anchor.x, candidate.y, anchor.z),
            correction = anchor.horizontalDistanceTo(candidate),
        )
    }

    companion object {
        /** Above this the constraint is doing violence to the measurement; say so. */
        const val NOTABLE_CORRECTION_METRES = 0.15
    }
}

data class ConstrainedPoint(val position: Vec3, val correction: Double) {
    val isNotable: Boolean
        get() = correction > MeasurementMode.NOTABLE_CORRECTION_METRES
}
