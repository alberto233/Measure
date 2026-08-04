package com.measure.core.geometry.capture

/** What a tap means, once the walk round a room has come back near where it started. */
enum class ClosingIntent {
    /** An ordinary corner, wherever it happens to be. */
    ADD_CORNER,

    /** A corner, but near enough the start that the interface should say which it was. */
    APPROACHING_START,

    /** A second reading of the first corner, which ends the capture. */
    CLOSE_LOOP,
}

/**
 * When a tap finishes a room rather than extending it.
 *
 * Here rather than in the capture screen because it is an accuracy rule, not an interface
 * one. The closing tap is a *second reading of the first corner*, and the gap between the
 * two readings is the accumulated drift the compass-rule adjustment distributes round the
 * perimeter (docs/ACCURACY.md M7). Anything counted as a close that was not actually the
 * same corner feeds the solver a "drift" that is really the width of a kitchen unit, and
 * quietly smears it over every wall in the room.
 */
object LoopClosure {

    /** A polygon needs three corners before it encloses anything. */
    const val MINIMUM_CORNERS = 3

    /**
     * Tap within this of the first corner and the loop closes instead of growing.
     *
     * Deliberately tight — this is not a convenience radius. A radius loose enough to be
     * comfortable is also loose enough to swallow a real corner near the doorway the walk
     * began at: an alcove, a chimney breast, the corner of a fitted unit. Losing a corner
     * is silent and permanent, whereas an unwanted extra corner is visible on the minimap
     * and one undo away.
     */
    const val CLOSING_RADIUS_METRES = 0.18

    /** Near the first corner, but not near enough to be a second reading of it. */
    const val APPROACH_RADIUS_METRES = 0.75

    /**
     * @param distanceToStart horizontal distance from the first corner, or null if there
     *   is no first corner yet.
     */
    fun classify(cornerCount: Int, distanceToStart: Double?): ClosingIntent = when {
        cornerCount < MINIMUM_CORNERS || distanceToStart == null -> ClosingIntent.ADD_CORNER
        distanceToStart <= CLOSING_RADIUS_METRES -> ClosingIntent.CLOSE_LOOP
        distanceToStart <= APPROACH_RADIUS_METRES -> ClosingIntent.APPROACHING_START
        else -> ClosingIntent.ADD_CORNER
    }
}
