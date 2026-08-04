package com.measure.core.geometry.plan

import com.measure.core.geometry.Vec2
import kotlin.math.abs

/** A direction a measurement may be straightened onto, with something to call it. */
data class PreferredDirection(val direction: Vec2, val description: String)

data class Straightened(
    val position: Vec2,
    /** How far the point moved to get here, in metres. */
    val correction: Double,
    /** What it was straightened onto, or null if it was left alone. */
    val description: String?,
) {
    /** Above this the constraint is doing violence to the measurement, and should say so. */
    val isNotable: Boolean get() = correction > NOTABLE_CORRECTION_METRES

    companion object {
        const val NOTABLE_CORRECTION_METRES = 0.15
    }
}

/**
 * Straightens a measurement that was meant to be square and was drawn by finger.
 *
 * The same argument as [com.measure.core.geometry.capture.MeasurementMode]: this is not
 * tidying, it *removes* error. "How far is the bed from the wall" means the perpendicular
 * distance, and a line drawn five degrees off perpendicular is not a worse drawing of that
 * distance — it is a measurement of something else, and it always reads long. A finger on
 * a phone-sized plan cannot hold five degrees over a two-metre span, and it does not have
 * to: the wall's direction is already known exactly.
 *
 * The correction is reported rather than applied silently, because a large one means the
 * user was pointing somewhere this constraint does not describe.
 */
object PlanConstraints {

    /**
     * How far off a preferred direction a line may be and still be snapped onto it.
     *
     * Wide enough to catch what a finger does — a couple of centimetres of slip over a
     * metre is about a degree, and people are far worse than that — and narrow enough
     * that a deliberately diagonal measurement is left alone.
     */
    const val SNAP_TOLERANCE_DEGREES = 12.0

    /**
     * The line snaps when it is within [SNAP_TOLERANCE_DEGREES] *of* the direction, so the
     * threshold is the cosine of that angle. Writing `cos(90 - tolerance)` here instead —
     * the sine — made everything within seventy-eight degrees snap, which a test caught by
     * watching a forty-five degree diagonal get straightened into a wall.
     */
    private val cosineTolerance = kotlin.math.cos(Math.toRadians(SNAP_TOLERANCE_DEGREES))

    /**
     * Projects [candidate] onto whichever preferred direction through [anchor] it is
     * nearly on already, or leaves it where it is.
     *
     * Directions are tried best first and are treated as lines rather than rays, so a
     * wall's normal catches a point on either side of it without the caller having to
     * work out which way is out of the room.
     */
    fun straighten(
        anchor: Vec2,
        candidate: Vec2,
        preferred: List<PreferredDirection>,
    ): Straightened {
        val offset = candidate - anchor
        val distance = offset.length
        if (distance < Vec2.EPSILON) return Straightened(candidate, 0.0, null)

        val unit = offset / distance

        preferred.forEach { option ->
            val direction = option.direction.normalised()
            if (direction.length < Vec2.EPSILON) return@forEach

            // Sign-free: a direction and its opposite describe the same line, and which
            // one a wall's normal happens to point is an artefact of how it was captured.
            val alignment = abs(unit dot direction)
            if (alignment < cosineTolerance) return@forEach

            val along = offset dot direction
            val projected = anchor + direction * along
            return Straightened(
                position = projected,
                correction = candidate.distanceTo(projected),
                description = option.description,
            )
        }

        return Straightened(candidate, 0.0, null)
    }
}
