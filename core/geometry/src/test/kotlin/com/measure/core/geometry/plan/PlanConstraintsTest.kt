package com.measure.core.geometry.plan

import com.measure.core.geometry.Vec2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlanConstraintsTest {

    /** A wall running along x, so measuring away from it means going along y. */
    private val awayFromWall = listOf(PreferredDirection(Vec2(0.0, 1.0), "square to wall 1"))

    private fun assertVec(expected: Vec2, actual: Vec2, tolerance: Double = 1e-9) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
    }

    @Test
    fun `a nearly square line is made square, and the length shortens to match`() {
        // Two metres out from the wall, and 15 cm adrift along it — about four degrees,
        // which is better aim than most people manage. Left alone it reads 2.006 m; the
        // distance actually asked for is 2.
        val result = PlanConstraints.straighten(
            anchor = Vec2(1.0, 0.0),
            candidate = Vec2(1.15, 2.0),
            preferred = awayFromWall,
        )

        assertVec(Vec2(1.0, 2.0), result.position)
        assertEquals(2.0, result.position.distanceTo(Vec2(1.0, 0.0)), 1e-9)
        assertEquals(0.15, result.correction, 1e-9)
        assertEquals("square to wall 1", result.description)
        assertFalse(result.isNotable)
    }

    @Test
    fun `the constraint works on either side of the wall`() {
        // The direction given points one way; a point on the other side is still square
        // to the wall, and which way a captured normal happens to face means nothing.
        val result = PlanConstraints.straighten(Vec2(1.0, 0.0), Vec2(1.1, -2.0), awayFromWall)
        assertVec(Vec2(1.0, -2.0), result.position)
        assertEquals("square to wall 1", result.description)
    }

    @Test
    fun `a deliberate diagonal is left alone`() {
        val result = PlanConstraints.straighten(Vec2(0.0, 0.0), Vec2(3.0, 3.0), awayFromWall)
        assertVec(Vec2(3.0, 3.0), result.position)
        assertEquals(0.0, result.correction, 1e-12)
        assertNull(result.description)
    }

    @Test
    fun `a big correction is flagged rather than applied quietly`() {
        // Eleven degrees off over four metres. Inside the snap tolerance, but three
        // quarters of a metre of movement is worth a word.
        val result = PlanConstraints.straighten(
            anchor = Vec2(0.0, 0.0),
            candidate = Vec2(0.78, 4.0),
            preferred = awayFromWall,
        )
        assertVec(Vec2(0.0, 4.0), result.position)
        assertTrue(result.isNotable, "a 78 cm move should be reported")
    }

    @Test
    fun `the first matching direction wins`() {
        // A wall's own square-on direction is offered before the plan's grid, so a
        // measurement off a slightly skewed wall follows that wall and not the grid.
        val skewed = Vec2(0.05, 1.0).normalised()
        val result = PlanConstraints.straighten(
            anchor = Vec2.ZERO,
            candidate = Vec2(0.1, 2.0),
            preferred = listOf(
                PreferredDirection(skewed, "square to wall 2"),
                PreferredDirection(Vec2(0.0, 1.0), "up the plan"),
            ),
        )
        assertEquals("square to wall 2", result.description)
    }

    @Test
    fun `two points in the same place are not straightened`() {
        val result = PlanConstraints.straighten(Vec2(1.0, 1.0), Vec2(1.0, 1.0), awayFromWall)
        assertVec(Vec2(1.0, 1.0), result.position)
        assertNull(result.description)
    }

    // --- wall to wall -----------------------------------------------------------------

    /** The far wall of a 4 m room: runs along x at y = 4. */
    private val farWall = Vec2(0.0, 4.0) to Vec2(6.0, 4.0)

    @Test
    fun `a wall to wall measurement is slid square along the far wall`() {
        // Started on a wall running along x, tapped the opposite wall 20 cm adrift —
        // about three degrees over four metres, which is a good aim. The reading was
        // 4.005 m; square across, it is 4.
        val t = PlanConstraints.squareAlongWall(
            from = Vec2(2.0, 0.0),
            reference = Vec2(1.0, 0.0),
            wallStart = farWall.first,
            wallEnd = farWall.second,
            currentPosition = Vec2(2.2, 4.0),
        )

        assertEquals(2.0 / 6.0, t!!, 1e-9)
        val squared = farWall.first + (farWall.second - farWall.first) * t
        assertEquals(4.0, squared.distanceTo(Vec2(2.0, 0.0)), 1e-9)
    }

    @Test
    fun `an end that landed on the wall stays on the wall`() {
        // The whole point of sliding rather than projecting: the anchor survives, so the
        // measurement still follows the wall when the room is re-solved.
        val t = PlanConstraints.squareAlongWall(
            from = Vec2(2.0, 0.0),
            reference = Vec2(1.0, 0.0),
            wallStart = farWall.first,
            wallEnd = farWall.second,
            currentPosition = Vec2(2.2, 4.0),
        )!!
        val squared = farWall.first + (farWall.second - farWall.first) * t
        assertEquals(4.0, squared.y, 1e-9)
    }

    @Test
    fun `a line well off square is left as drawn`() {
        // Fifteen degrees. Beyond that the user meant a diagonal, and squaring it would
        // be answering a different question.
        assertNull(
            PlanConstraints.squareAlongWall(
                from = Vec2(2.0, 0.0),
                reference = Vec2(1.0, 0.0),
                wallStart = farWall.first,
                wallEnd = farWall.second,
                currentPosition = Vec2(3.07, 4.0),
            ),
        )
    }

    @Test
    fun `a square crossing that would fall off the end of the wall is refused`() {
        // Starting beyond where the far wall reaches: there is no point on it square to
        // this one, and inventing a position past its end would put the measurement in
        // mid-air.
        assertNull(
            PlanConstraints.squareAlongWall(
                from = Vec2(9.0, 0.0),
                reference = Vec2(1.0, 0.0),
                wallStart = farWall.first,
                wallEnd = farWall.second,
                currentPosition = Vec2(9.0, 4.0),
            ),
        )
    }

    @Test
    fun `a wall running the same way as the reference has nothing to solve`() {
        // Every point on it is equally square, so there is no unique answer to give.
        assertNull(
            PlanConstraints.squareAlongWall(
                from = Vec2(0.0, 0.0),
                reference = Vec2(0.0, 1.0),
                wallStart = farWall.first,
                wallEnd = farWall.second,
                currentPosition = Vec2(0.1, 4.0),
            ),
        )
    }

    @Test
    fun `nothing preferred means nothing moves`() {
        val result = PlanConstraints.straighten(Vec2.ZERO, Vec2(0.1, 2.0), emptyList())
        assertVec(Vec2(0.1, 2.0), result.position)
        assertNull(result.description)
    }
}
