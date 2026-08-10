package com.traza.core.geometry.capture

import com.traza.core.geometry.AngleSnapper
import com.traza.core.geometry.Vec2
import com.traza.core.geometry.WallBearing
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the reticle locked onto, so the interface can say which and the user can refuse. */
enum class SnapKind {
    /** Nothing. [CornerHint.position] is the raw observation, untouched. */
    NONE,

    /** Held on the rectilinear wall running from the last corner. One degree of freedom left. */
    WALL_BEARING,

    /**
     * Fully determined: the outgoing wall and the wall back to the start meet here.
     *
     * The fourth corner of a rectangle has no freedom left once the other three are known,
     * and this is the snap that feels like corner detection rather than like assistance.
     */
    CORNER_INTERSECTION,
}

/**
 * Where the corner is, given where the user aimed and what the room has shown so far.
 *
 * [observed] is always the raw hit and is never modified. That separation is the whole
 * contract: `CornerEntity` keeps `measuredX/measuredY` (observed) apart from `x/y` (solved)
 * so that re-solving is idempotent, and a capture-time snap has to respect it. Write
 * [position] to the solved columns and [observed] to the measured ones, and turning this
 * feature off later — or reverting it outright — re-solves every room captured while it was
 * on back to exactly what the camera saw.
 */
data class CornerHint(
    /** Where to place the corner. Identical to [observed] when [kind] is [SnapKind.NONE]. */
    val position: Vec2,
    /** What the camera actually reported. Never adjusted. */
    val observed: Vec2,
    val kind: SnapKind,
) {
    /** How far the snap moved the point, in metres. Zero when nothing snapped. */
    val correction: Double get() = observed.distanceTo(position)

    val isSnapped: Boolean get() = kind != SnapKind.NONE
}

/**
 * Brings the rectilinear prior forward from the solve into the aim — docs/ACCURACY.md M6.
 *
 * Targeting error is one to three centimetres, uncorrelated between points, and it is the
 * error a person can feel: holding a reticle exactly on a floor corner, one-handed, at
 * arm's length, is the fiddliest part of capturing a room. `AngleSnapper` already removes
 * most of its consequence *after* the fact, by pulling wall bearings onto the room's frame
 * during the solve. This applies the same prior *while aiming*, so the corner lands where
 * the room says it should and the user does not have to.
 *
 * The gain is a reduction in degrees of freedom rather than in sensing. Once one wall is
 * known, the next runs at a right angle to it, so only the distance along that wall is
 * still in the user's hands — two degrees of freedom become one. Once three corners of a
 * rectangle are known the fourth is determined outright, and becomes zero.
 *
 * **This adds no new sensing and cannot invent a corner.** It only moves a point the user
 * has already aimed at, never further than [maxCorrection], and never at all when the wall
 * they are describing is not rectilinear — a genuine bay or a splayed wall falls outside
 * [tolerance] and is left exactly as measured. That matters because the failure this
 * project has already had (M11, withdrawn) was a mechanism that returned confident wrong
 * numbers; this one declines rather than guesses, and says which it did.
 */
class CornerSnapper(
    /** How far off square the aim may be and still be treated as a right angle. */
    val tolerance: Double = AngleSnapper.DEFAULT_TOLERANCE,
    /**
     * The furthest the snap may move a point, in metres.
     *
     * An independent cap on top of [tolerance], and load-bearing because an angular
     * tolerance is a widening distance: 6° at 4 m is 42 cm, which is not a correction, it
     * is a relocation. Beyond this the aim is taken at face value.
     */
    val maxCorrection: Double = DEFAULT_MAX_CORRECTION,
    /** Whether 45° walls are part of the frame, matching [AngleSnapper]. */
    val allowDiagonals: Boolean = false,
    /** The shortest run from the previous corner that carries a usable bearing, in metres. */
    val minimumRun: Double = DEFAULT_MINIMUM_RUN,
) {

    /**
     * @param captured the corners walked so far, in order.
     * @param observed where the camera says the user is aiming.
     */
    fun hint(captured: List<Vec2>, observed: Vec2): CornerHint {
        // One corner establishes no wall, and no wall establishes no frame. The first two
        // corners of a room are always taken exactly as aimed.
        if (captured.size < 2) return CornerHint(observed, observed, SnapKind.NONE)

        val last = captured.last()
        // A bearing inferred from a few centimetres of baseline is noise, and a corner
        // placed that close to the previous one is a double tap rather than a wall. Both
        // are left alone: there is nothing here to be confident about.
        if (observed.distanceTo(last) < minimumRun) {
            return CornerHint(observed, observed, SnapKind.NONE)
        }

        val axis = AngleSnapper.axisOf(
            captured.zipWithNext { from, to ->
                val run = to - from
                WallBearing(run.bearing, run.length)
            },
        )

        val outgoing = snapBearing(axis, (observed - last).bearing)
            ?: return CornerHint(observed, observed, SnapKind.NONE)

        // The closing wall runs back to the first corner and is square to the outgoing one,
        // which pins the corner completely. Preferred over the bearing snap when it is in
        // reach, because it is the stronger claim and the one with no freedom left in it.
        cornerIntersection(captured, last, outgoing)
            ?.takeIf { it.distanceTo(observed) <= maxCorrection }
            ?.let { return CornerHint(it, observed, SnapKind.CORNER_INTERSECTION) }

        // Always forward along the wall, never behind it: the bearing chosen above is the
        // *nearest* of the frame's four, so the aim is within [tolerance] of it and the
        // projection cannot come out negative.
        val direction = Vec2.fromBearing(outgoing)
        val onWall = last + direction * (observed - last).dot(direction)
        if (onWall.distanceTo(observed) > maxCorrection) {
            return CornerHint(observed, observed, SnapKind.NONE)
        }
        return CornerHint(onWall, observed, SnapKind.WALL_BEARING)
    }

    /**
     * Applies [hint] down a whole walk, each corner squared against the ones before it.
     *
     * Progressive, and over the *snapped* chain rather than the raw one, because that is
     * what the user watched happen: each corner was placed against a plan that already had
     * the previous corners squared into it. Re-deriving the room from raw observations at
     * the end would produce a different room from the one on screen, and the live plan
     * would have been a lie.
     *
     * [assisted] says, per corner, whether the assist was switched on when it was captured.
     * A corner taken with it off is passed through exactly as observed **and stays that
     * way** — switching the assist back on later must not reach backwards and square it.
     * Somebody who turned the assist off to capture a bay did so deliberately, and having
     * that bay silently straightened when they turned it on again for the next wall
     * destroys the very intent the toggle exists to express. Corners taken with it off
     * still inform the frame the later ones snap to, because they are real walls.
     *
     * Returns positions only. The caller keeps the observations — see [CornerHint].
     */
    fun snapChain(observed: List<Vec2>, assisted: List<Boolean>): List<Vec2> {
        val snapped = ArrayList<Vec2>(observed.size)
        observed.forEachIndexed { index, point ->
            snapped += if (assisted.getOrElse(index) { false }) {
                hint(snapped, point).position
            } else {
                point
            }
        }
        return snapped
    }

    /** The whole walk captured with the assist on. */
    fun snapChain(observed: List<Vec2>): List<Vec2> =
        snapChain(observed, List(observed.size) { true })

    /** The frame bearing nearest [bearing], or null if the aim is too far off it to be square. */
    private fun snapBearing(axis: Double, bearing: Double): Double? {
        val step = if (allowDiagonals) PI / 4 else PI / 2
        val target = axis + ((bearing - axis) / step).roundToInt() * step
        return target.takeIf { abs(AngleSnapper.normalise(bearing - it)) <= tolerance }
    }

    /**
     * Where the outgoing wall meets the wall running back to the start.
     *
     * **Only ever offered for the fourth corner of a four-corner room**, and the narrowness
     * is the point. This is the one snap here that moves a point *along* a wall rather than
     * merely across it, so unlike the bearing snap it changes a measured length — up to
     * [maxCorrection] of it. That is only defensible where the answer is genuinely
     * determined rather than merely plausible.
     *
     * With exactly three corners and a rectilinear frame it is determined: the fourth
     * corner of a rectangle has no freedom left. With more, the wall running back to the
     * start need not be square to the outgoing one at all — walk an L-shaped room and at
     * the third corner this would compute a confident intersection that is not a corner of
     * anything. Predicting it anyway would be exactly the fault that got M11 withdrawn: a
     * mechanism returning a wrong number with no sign that it had guessed.
     */
    private fun cornerIntersection(
        captured: List<Vec2>,
        last: Vec2,
        outgoing: Double,
    ): Vec2? {
        if (captured.size != 3) return null
        val start = captured.first()
        val closing = outgoing + PI / 2

        return intersect(last, Vec2.fromBearing(outgoing), start, Vec2.fromBearing(closing))
    }

    /**
     * Intersection of two infinite lines, each a point and a direction.
     *
     * The parallel case cannot arise from the only caller, which builds the second
     * direction at exactly 90° to the first. Guarded anyway rather than asserted away: it
     * costs one comparison, and a future caller passing two arbitrary walls would otherwise
     * get an infinity here and a corner somewhere outside the building.
     */
    private fun intersect(a: Vec2, da: Vec2, b: Vec2, db: Vec2): Vec2? {
        val denominator = da.cross(db)
        if (abs(denominator) < Vec2.EPSILON) return null
        return a + da * ((b - a).cross(db) / denominator)
    }

    companion object {
        /**
         * Twenty-five centimetres.
         *
         * Comfortably more than the targeting error being corrected, so the assist actually
         * engages, and far less than the width of anything the user might have meant
         * instead — a doorway reveal, an alcove, a chimney breast.
         */
        const val DEFAULT_MAX_CORRECTION = 0.25

        /**
         * Thirty centimetres.
         *
         * Shorter than any wall of a room worth capturing, and long enough that the bearing
         * taken from it is not dominated by the very targeting error being corrected.
         */
        const val DEFAULT_MINIMUM_RUN = 0.30
    }
}
