package com.measure.core.geometry

import com.measure.core.geometry.capture.CornerSnapper
import com.measure.core.geometry.capture.SnapKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.hypot

/**
 * The capture-time rectilinear assist.
 *
 * Two properties carry the whole feature and are asserted everywhere below: the raw
 * observation is never modified, and the snap declines rather than guesses. Everything else
 * is a consequence.
 */
class CornerSnapperTest {

    private val snapper = CornerSnapper()

    /** A 5 × 4 m room, walked anticlockwise from the origin. */
    private val threeCornersOfARectangle =
        listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 4.0))

    @Test
    @DisplayName("the first two corners have no frame to snap to")
    fun `no frame yet`() {
        for (captured in listOf(emptyList(), listOf(Vec2(0.0, 0.0)))) {
            val hint = snapper.hint(captured, Vec2(3.0, 0.4))

            assertEquals(SnapKind.NONE, hint.kind)
            assertEquals(Vec2(3.0, 0.4), hint.position)
        }
    }

    @Test
    @DisplayName("a wall aimed slightly off square is pulled onto it")
    fun `bearing snap`() {
        // Aiming at (5, 3) from (5, 0) but 12 cm out sideways: a wall 2.3 degrees off.
        val hint = snapper.hint(
            captured = listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0)),
            observed = Vec2(5.12, 3.0),
        )

        assertEquals(SnapKind.WALL_BEARING, hint.kind)
        assertClose(Vec2(5.0, 3.0), hint.position)
        // The correction is perpendicular to the wall only: the distance along it, which is
        // what the user is actually measuring, comes through untouched.
        assertEquals(0.12, hint.correction, 1e-9)
    }

    @Test
    @DisplayName("a real bay is left exactly as measured")
    fun `beyond tolerance does not snap`() {
        // 20 degrees off square is not a mis-aim, it is a wall that is not square.
        val observed = Vec2(5.0 + 3.0 * 0.364, 3.0)
        val hint = snapper.hint(listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0)), observed)

        assertEquals(SnapKind.NONE, hint.kind)
        assertSame(observed, hint.position)
    }

    @Test
    @DisplayName("an angular tolerance is a widening distance, so the correction is capped")
    fun `correction cap`() {
        // 4 degrees at 6 m is 42 cm — inside the angular tolerance, far outside anything
        // that should be called a correction.
        val hint = snapper.hint(
            captured = listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0)),
            observed = Vec2(5.0 + 0.42, 6.0),
        )

        assertEquals(SnapKind.NONE, hint.kind)
        assertTrue(
            hint.correction == 0.0,
            "A snap this large is a relocation, not a correction.",
        )
    }

    @Test
    @DisplayName("the fourth corner of a rectangle is determined, and locks")
    fun `corner intersection`() {
        // Aimed 9 cm short and 7 cm wide of the true corner at (0, 4).
        val hint = snapper.hint(threeCornersOfARectangle, Vec2(0.09, 3.93))

        assertEquals(SnapKind.CORNER_INTERSECTION, hint.kind)
        assertClose(Vec2(0.0, 4.0), hint.position)
        assertEquals(Vec2(0.09, 3.93), hint.observed)
    }

    /**
     * The case that forced the fourth-corner restriction.
     *
     * Walking an L-shaped room, the third corner is *not* where the wall back to the start
     * meets the outgoing wall — the closing wall is two walls away and need not be square
     * to this one. An unrestricted intersection snap computes a confident answer here and
     * moves the corner along the wall to reach it, silently changing a measured length.
     *
     * That is the shape of the fault that got M11 withdrawn, so it is asserted against
     * rather than left to the comment.
     */
    @Test
    @DisplayName("an L-shaped room never gets an intersection snap")
    fun `intersection is only ever offered for a fourth corner`() {
        val lShape = listOf(
            Vec2(0.0, 0.0),
            Vec2(6.0, 0.0),
            Vec2(6.0, 3.0),
            Vec2(3.0, 3.0),
        )

        val hint = snapper.hint(lShape, Vec2(3.02, 5.0))

        assertEquals(
            SnapKind.WALL_BEARING,
            hint.kind,
            "Only the bearing may be assisted here; the corner is not determined.",
        )
        // Moved across the wall to square it, and not one millimetre along it.
        assertClose(Vec2(3.0, 5.0), hint.position)
    }

    /**
     * A bearing needs a baseline, and 4 cm is not one.
     *
     * This test replaced one asserting that aiming *behind* the previous corner is refused.
     * That case cannot arise: the bearing is snapped to the nearest of the frame's four, so
     * the aim is always within tolerance of the direction chosen and the projection is
     * always forward. The guard written for it was unreachable. The reachable degenerate
     * case is the opposite one — a second tap almost on top of the last corner, where the
     * direction is dominated by the very error being corrected.
     */
    @Test
    @DisplayName("a tap almost on the previous corner has no bearing to work from")
    fun `too short a run`() {
        val observed = Vec2(5.04, 0.02)
        val hint = snapper.hint(listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0)), observed)

        assertEquals(SnapKind.NONE, hint.kind)
        assertSame(observed, hint.position)
    }

    @Test
    @DisplayName("a wall running back the other way is a legitimate wall")
    fun `the frame runs both ways`() {
        // Not every room is walked in the direction the first wall happened to run. A
        // corner at -90 degrees to the frame is as square as one at +90.
        val hint = snapper.hint(listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0)), Vec2(4.98, -2.0))

        assertEquals(SnapKind.WALL_BEARING, hint.kind)
        assertClose(Vec2(5.0, -2.0), hint.position)
    }

    @Test
    @DisplayName("snapping an already-snapped point moves it no further")
    fun `idempotent`() {
        val captured = listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0))
        val once = snapper.hint(captured, Vec2(5.12, 3.0))
        val twice = snapper.hint(captured, once.position)

        assertClose(once.position, twice.position)
        assertEquals(0.0, twice.correction, 1e-9)
    }

    /**
     * The invariant the revert story rests on.
     *
     * `CornerEntity` keeps the observation apart from the solution so that re-solving is
     * idempotent. If a snapped point ever reached the observed field, the snap would be
     * baked into the measurement and no amount of turning the feature off would recover
     * what the camera saw.
     */
    @Test
    @DisplayName("the observation survives every snap unchanged")
    fun `observation is never modified`() {
        val cases = listOf(
            threeCornersOfARectangle to Vec2(0.09, 3.93),
            listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0)) to Vec2(5.12, 3.0),
            listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0)) to Vec2(7.0, 3.0),
            emptyList<Vec2>() to Vec2(1.0, 1.0),
        )

        for ((captured, observed) in cases) {
            assertEquals(observed, snapper.hint(captured, observed).observed)
        }
    }

    @Test
    @DisplayName("a room built on a skewed frame snaps to that frame, not to north")
    fun `frame is the room's own`() {
        // The same rectangle, rotated 30 degrees. Nothing here is axis-aligned, and the
        // assist has to follow the room rather than the world.
        val rotation = Math.toRadians(30.0)
        val captured = listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 4.0))
            .map { it.rotated(rotation) }
        val trueCorner = Vec2(0.0, 4.0).rotated(rotation)

        val hint = snapper.hint(captured, trueCorner + Vec2(0.06, -0.05))

        assertEquals(SnapKind.CORNER_INTERSECTION, hint.kind)
        assertClose(trueCorner, hint.position)
    }

    @Test
    @DisplayName("diagonals snap only when the room is allowed them")
    fun `diagonals`() {
        val captured = listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0))
        // A wall running at 44 degrees: a mis-aimed 45 if diagonals are in the frame, and
        // a deliberate non-square wall if they are not.
        val observed = Vec2(5.0, 0.0) + Vec2.fromBearing(Math.toRadians(44.0)) * 3.0

        assertEquals(SnapKind.NONE, CornerSnapper().hint(captured, observed).kind)
        assertEquals(
            SnapKind.WALL_BEARING,
            CornerSnapper(allowDiagonals = true).hint(captured, observed).kind,
        )
    }

    @Test
    @DisplayName("a whole walk squares up, and a clean walk is left alone")
    fun `snap chain`() {
        val wobbly = listOf(
            Vec2(0.0, 0.0),
            Vec2(5.0, 0.0),
            Vec2(5.08, 4.0),
            Vec2(0.06, 4.05),
        )

        val squared = snapper.snapChain(wobbly)

        // The first two are always taken as aimed; the rest come onto the frame.
        assertClose(Vec2(0.0, 0.0), squared[0])
        assertClose(Vec2(5.0, 0.0), squared[1])
        assertClose(Vec2(5.0, 4.0), squared[2])
        assertClose(Vec2(0.0, 4.0), squared[3])

        // Idempotent over the whole walk, not just per corner: re-running must not creep.
        assertClose(squared[3], snapper.snapChain(squared)[3])
    }

    /**
     * A cut corner survives the walk.
     *
     * The first version of this test used a wholly irregular quadrilateral and asserted
     * nothing moved. It was wrong, and instructively so: the frame is a length-weighted
     * mean of the walls themselves, so an irregular shape pulls the axis towards its own
     * walls and several of them end up within tolerance of it. "Not a rectangle" does not
     * imply "not rectilinear".
     *
     * A 45° splay is unambiguous — it is 45° from the frame whichever way the frame is
     * fitted — so it is the honest way to assert that a real architectural angle is left
     * alone. With `allowDiagonals` it would snap, which is the point of that flag.
     */
    @Test
    @DisplayName("a deliberate 45 degree splay is not squared away")
    fun `snap chain leaves a real angle alone`() {
        val withSplay = listOf(
            Vec2(0.0, 0.0),
            Vec2(5.0, 0.0),
            Vec2(5.0, 3.0),
            Vec2(3.5, 4.5),
        )

        assertEquals(withSplay, snapper.snapChain(withSplay))
    }

    private fun assertClose(expected: Vec2, actual: Vec2, tolerance: Double = 1e-6) {
        assertTrue(
            hypot(expected.x - actual.x, expected.y - actual.y) <= tolerance,
            "expected $expected but was $actual",
        )
    }
}
