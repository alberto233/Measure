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

    @Test
    fun `nothing preferred means nothing moves`() {
        val result = PlanConstraints.straighten(Vec2.ZERO, Vec2(0.1, 2.0), emptyList())
        assertVec(Vec2(0.1, 2.0), result.position)
        assertNull(result.description)
    }
}
