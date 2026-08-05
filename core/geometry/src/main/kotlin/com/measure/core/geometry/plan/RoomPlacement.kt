package com.measure.core.geometry.plan

import com.measure.core.geometry.Vec2

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
}
