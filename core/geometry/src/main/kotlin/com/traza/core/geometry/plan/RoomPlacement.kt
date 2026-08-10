package com.traza.core.geometry.plan

import com.traza.core.geometry.Vec2
import kotlin.math.PI
import kotlin.math.round

/**
 * Where to put a room that was measured in a different world frame.
 *
 * ARCore starts every session with the origin wherever the phone was and the axes pointing
 * wherever it was pointing. A room captured in one visit and a room captured in the next
 * are therefore described in two unrelated coordinate systems, and drawing both on one plan
 * at their stored coordinates produces something that looks like a measurement of how the
 * two rooms sit together — overlapping, or metres apart — and is nothing of the kind. It is
 * a picture of where the user happened to be standing when they opened the app.
 *
 * That is the worst class of bug this app can have. A wrong number the user can check; a
 * confidently drawn relationship that was never measured, they cannot.
 *
 * The honest response is not to invent a position but to make it obvious none was measured:
 * the new room is set down clear of everything already on the plan, in a row, with a gap.
 * Nobody reads a room floating a metre off the others as a survey of the building — and
 * from there the user can drag it where it belongs, which is a placement they made and
 * know the provenance of.
 *
 * Translation only, never rotation. The rooms' internal geometry is real and must survive
 * untouched, and a guessed rotation would be a second invented number on top of the first.
 */
object RoomPlacement {

    /**
     * Clear air between one room and the next, in metres.
     *
     * Wide enough that two rooms never read as sharing a wall, which is exactly the
     * relationship this placement is not entitled to claim.
     */
    const val GAP_METRES = 1.0

    /**
     * How far to shift [incoming] so it sits clear of [existing].
     *
     * [Vec2.ZERO] when there is nothing to clear, so a first room keeps the coordinates it
     * was captured in and the common case costs nothing.
     *
     * Placed to the right and aligned along the near edge, so a plan assembled over several
     * visits reads left to right in capture order.
     */
    fun offsetFor(existing: List<Vec2>, incoming: List<Vec2>): Vec2 {
        if (existing.isEmpty() || incoming.isEmpty()) return Vec2.ZERO

        val right = existing.maxOf { it.x }
        val near = existing.minOf { it.y }

        return Vec2(
            x = right + GAP_METRES - incoming.minOf { it.x },
            y = near - incoming.minOf { it.y },
        )
    }

    /**
     * Where a room turns about, which is the centre of what it occupies.
     *
     * The bounding box rather than the centroid, so a room appears to spin on the spot
     * instead of swinging around a point that depends on its shape. An L-shaped room's
     * centroid can sit well off centre — and in the worst case outside the room — which
     * would send it wandering across the plan every time it was rotated.
     */
    fun centre(outline: List<Vec2>): Vec2 {
        if (outline.isEmpty()) return Vec2.ZERO
        return Vec2(
            x = (outline.minOf { it.x } + outline.maxOf { it.x }) / 2.0,
            y = (outline.minOf { it.y } + outline.maxOf { it.y }) / 2.0,
        )
    }

    fun rotateAbout(point: Vec2, radians: Double, pivot: Vec2): Vec2 =
        pivot + (point - pivot).rotated(radians)

    /**
     * The smallest turn that lines this room's walls up with [reference].
     *
     * The counterpart to the row of unplaced rooms: a room arrives at whatever angle the
     * user was facing when they started capturing, and translation alone can never fix
     * that — it leaves a plan whose pieces cannot be turned. This is one tap of the
     * arranging that follows, and for a rectilinear room it is almost all of it.
     *
     * Both directions describe a **grid**, and a grid has four-fold symmetry: turning a
     * room 90° lines it up just as well as leaving it. So the answer is folded into
     * ±45°, which is the smallest turn that does the job. Choosing which of the four
     * quarter turns is wanted is a question about doors and daylight that the geometry
     * cannot answer, and is left to the user and two buttons.
     *
     * Returns zero when the room has no direction worth speaking of.
     */
    fun squaringAngle(outline: List<Vec2>, reference: Vec2): Double {
        if (outline.size < 3 || reference.length < Vec2.EPSILON) return 0.0
        val own = DimensionChains.dominantDirection(listOf(outline))
        if (own.length < Vec2.EPSILON) return 0.0

        val quarter = PI / 2
        val delta = reference.bearing - own.bearing
        return delta - quarter * round(delta / quarter)
    }
}
