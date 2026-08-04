package com.measure.core.geometry.capture

import com.measure.core.geometry.Vec2
import com.measure.core.geometry.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI

class WallFaceTest {

    /** A wall through [origin] running along [along]. */
    private fun wall(
        id: Long,
        origin: Vec2,
        along: Vec2,
        sigma: Double = 0.02,
        extent: Double = 3.0,
    ): WallFace {
        val direction = along.normalised()
        return WallFace(
            id = id,
            origin = origin,
            direction = direction,
            normal = direction.perpendicular(),
            extent = extent,
            sigma = sigma,
        )
    }

    /** A 4 x 3 m room with corners at (0,0), (4,0), (4,3), (0,3), walls in walk order. */
    private val roomWalls = listOf(
        wall(1, Vec2(0.0, 0.0), Vec2(1.0, 0.0)),
        wall(2, Vec2(4.0, 0.0), Vec2(0.0, 1.0)),
        wall(3, Vec2(0.0, 3.0), Vec2(1.0, 0.0)),
        wall(4, Vec2(0.0, 0.0), Vec2(0.0, 1.0)),
    )

    private fun assertVec(expected: Vec2, actual: Vec2, tolerance: Double = 1e-9) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
    }

    // --- intersection ----------------------------------------------------------------

    @Test
    fun `two square walls cross where they should`() {
        val corner = WallIntersection.corner(roomWalls[0], roomWalls[1]).getOrThrow()
        assertVec(Vec2(4.0, 0.0), corner.position)
        assertEquals(PI / 2, corner.angle, 1e-9)
        assertTrue(corner.isWellConditioned)
    }

    @Test
    fun `a square crossing barely inflates the walls' own uncertainty`() {
        val corner = WallIntersection.corner(roomWalls[0], roomWalls[1]).getOrThrow()
        // hypot(2 cm, 2 cm) with no conditioning penalty at all.
        assertEquals(0.0283, corner.sigma, 1e-3)
    }

    @Test
    fun `a shallow crossing says so, in the number as well as the angle`() {
        // Thirty degrees apart: the intersection is twice as uncertain as either wall.
        val slanted = wall(9, Vec2(4.0, 0.0), Vec2(1.0, 0.0).rotated(PI / 6))
        val corner = WallIntersection.corner(roomWalls[0], slanted).getOrThrow()

        assertEquals(PI / 6, corner.angle, 1e-9)
        assertFalse(corner.isWellConditioned)
        assertEquals(2.0, corner.sigma / 0.0283, 0.02)
    }

    @Test
    fun `near parallel walls do not produce a corner`() {
        // The opposite wall of the room: parallel, and three metres away.
        val opposite = wall(9, Vec2(0.0, 3.0), Vec2(1.0, 0.0))
        val failure = WallIntersection.corner(roomWalls[0], opposite).exceptionOrNull()
        assertEquals(WallPairProblem.TOO_SHALLOW, (failure as WallPairFailure).problem)
    }

    @Test
    fun `the same wall taken twice is reported as that, not as a bad angle`() {
        // Aimed at the same wall from two places: a few centimetres apart, and the fit
        // is a degree or so off. This is the mistake a user actually makes.
        val again = wall(9, Vec2(2.0, 0.04), Vec2(1.0, 0.0).rotated(0.02))
        val failure = WallIntersection.corner(roomWalls[0], again).exceptionOrNull()
        assertEquals(WallPairProblem.SAME_WALL, (failure as WallPairFailure).problem)
    }

    @Test
    fun `a reflex corner reports the right angle it actually is`() {
        // An L-shaped room's inside corner. The walls are perpendicular; only which side
        // ARCore happened to fit them from differs, and that is not information.
        val flipped = roomWalls[1].copy(normal = -roomWalls[1].normal)
        val corner = WallIntersection.corner(roomWalls[0], flipped).getOrThrow()
        assertEquals(PI / 2, corner.angle, 1e-9)
        assertVec(Vec2(4.0, 0.0), corner.position)
    }

    // --- chains ----------------------------------------------------------------------

    @Test
    fun `four walls walked round close into four corners`() {
        val result = WallChain.corners(roomWalls, closed = true)

        assertTrue(result.isComplete)
        assertEquals(4, result.corners.size)
        assertVec(Vec2(4.0, 0.0), result.corners[0].position)
        assertVec(Vec2(4.0, 3.0), result.corners[1].position)
        assertVec(Vec2(0.0, 3.0), result.corners[2].position)
        assertVec(Vec2(0.0, 0.0), result.corners[3].position)
    }

    @Test
    fun `an open chain gives one corner fewer than it has walls`() {
        val result = WallChain.corners(roomWalls.take(3), closed = false)
        assertEquals(2, result.corners.size)
        assertVec(Vec2(4.0, 0.0), result.corners[0].position)
        assertVec(Vec2(4.0, 3.0), result.corners[1].position)
    }

    @Test
    fun `a single wall is a chain with no corners in it`() {
        assertEquals(0, WallChain.corners(roomWalls.take(1), closed = false).corners.size)
        assertEquals(0, WallChain.corners(roomWalls.take(2), closed = true).corners.size)
    }

    @Test
    fun `a bad pair is named by its first wall and does not stop the rest`() {
        // Wall 2 taken twice by mistake, so the pair (1, 2) cannot resolve.
        val walls = listOf(roomWalls[0], roomWalls[1], roomWalls[1].copy(id = 99), roomWalls[2])
        val result = WallChain.corners(walls, closed = false)

        assertFalse(result.isComplete)
        assertEquals(mapOf(1 to WallPairProblem.SAME_WALL), result.unresolved)
        assertEquals(2, result.corners.size)
    }

    @Test
    fun `the worst corner is the one worth reporting`() {
        val slanted = wall(9, Vec2(0.0, 3.0), Vec2(1.0, 0.0).rotated(PI / 6))
        val result = WallChain.corners(listOf(roomWalls[0], roomWalls[1], slanted), closed = false)
        assertEquals(result.corners.maxOf { it.sigma }, result.worstSigma)
    }

    // --- reading a wall off an ARCore plane ------------------------------------------

    @Test
    fun `a vertical plane becomes a line on the plan`() {
        val face = WallFace.fromVerticalPlane(
            id = 1,
            centre = Vec3(2.0, 1.2, -3.0),
            normal = Vec3(1.0, 0.0, 0.0),
            extent = 2.5,
            range = 2.0,
        )

        assertNotNull(face)
        // Vec3.toFloorPlane negates z, for both the point and the direction.
        assertVec(Vec2(2.0, 3.0), face!!.origin)
        assertVec(Vec2(1.0, 0.0), face.normal)
        assertEquals(0.012 + 0.005 * 2.0, face.sigma, 1e-12)
    }

    @Test
    fun `a floor is not a wall`() {
        assertNull(
            WallFace.fromVerticalPlane(1, Vec3.ZERO, Vec3(0.0, 1.0, 0.0), extent = 9.0, range = 2.0),
        )
    }

    @Test
    fun `a leaning surface is not a wall`() {
        // A staircase soffit at about 25 degrees off vertical.
        assertNull(
            WallFace.fromVerticalPlane(1, Vec3.ZERO, Vec3(0.9, 0.42, 0.0), extent = 2.0, range = 2.0),
        )
    }

    @Test
    fun `a cupboard door is not a wall`() {
        assertNull(
            WallFace.fromVerticalPlane(1, Vec3.ZERO, Vec3(1.0, 0.0, 0.0), extent = 0.4, range = 1.5),
        )
    }

    @Test
    fun `further away is less certain, the same way a point hit is`() {
        val near = WallFace.fromVerticalPlane(1, Vec3.ZERO, Vec3(1.0, 0.0, 0.0), 3.0, range = 1.0)!!
        val far = WallFace.fromVerticalPlane(2, Vec3.ZERO, Vec3(1.0, 0.0, 0.0), 3.0, range = 6.0)!!
        assertTrue(far.sigma > near.sigma)
    }
}
