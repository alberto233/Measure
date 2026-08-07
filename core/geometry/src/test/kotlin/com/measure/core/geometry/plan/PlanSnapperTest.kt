package com.measure.core.geometry.plan

import com.measure.core.geometry.Vec2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlanSnapperTest {

    /** A 5 x 4 m room, anticlockwise from the origin. */
    private val room = SnapRoom(
        id = 1,
        label = "Room 1",
        outline = listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 4.0), Vec2(0.0, 4.0)),
        cornerSigmas = listOf(0.010, 0.012, 0.014, 0.016),
    )

    private val rooms = listOf(room)
    private val reach = 0.3

    private fun assertVec(expected: Vec2, actual: Vec2, tolerance: Double = 1e-9) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
    }

    // --- snapping ---------------------------------------------------------------------

    @Test
    fun `a tap near a corner takes the corner, and says which`() {
        val point = PlanSnapper.snap(rooms, Vec2(4.92, 0.06), reach)

        assertEquals(SnapKind.CORNER, point.kind)
        assertEquals(PlanAnchor.Corner(1, 1), point.anchor)
        assertVec(Vec2(5.0, 0.0), point.position)
        assertEquals("Room 1 · corner 2", point.description)
        assertEquals(0.012, point.sigma, 1e-12)
    }

    @Test
    fun `a corner wins over the two walls it lies on`() {
        // Right on the corner, so both walls are also within reach. The other order would
        // make corners impossible to select at all.
        assertEquals(SnapKind.CORNER, PlanSnapper.snap(rooms, Vec2(0.0, 0.0), reach).kind)
    }

    @Test
    fun `a tap near a wall lands on the wall, at the right place along it`() {
        val point = PlanSnapper.snap(rooms, Vec2(2.0, 0.1), reach)

        assertEquals(SnapKind.WALL, point.kind)
        assertEquals(PlanAnchor.Wall(1, 0, 0.4), point.anchor)
        assertVec(Vec2(2.0, 0.0), point.position)
        assertEquals("Room 1 · wall 1", point.description)
    }

    @Test
    fun `a tap in open space stays where it was put`() {
        val point = PlanSnapper.snap(rooms, Vec2(2.5, 2.0), reach)

        assertEquals(SnapKind.FREE, point.kind)
        assertVec(Vec2(2.5, 2.0), point.position)
        assertEquals(PlanSnapper.PLACEMENT_SIGMA, point.sigma, 1e-12)
    }

    // --- uncertainty ------------------------------------------------------------------

    @Test
    fun `a point placed by finger is never as good as a captured corner`() {
        val corner = PlanSnapper.snap(rooms, Vec2(0.0, 0.0), reach)
        val free = PlanSnapper.snap(rooms, Vec2(2.5, 2.0), reach)
        val wall = PlanSnapper.snap(rooms, Vec2(2.0, 0.1), reach)

        assertTrue(corner.sigma < wall.sigma)
        assertTrue(wall.sigma < free.sigma)
    }

    @Test
    fun `a corner the solve moved is flagged as modelled`() {
        val snapped = room.copy(snappedCorners = setOf(2))
        assertTrue(PlanSnapper.snap(listOf(snapped), Vec2(5.0, 4.0), reach).isModelled)
        assertFalse(PlanSnapper.snap(listOf(snapped), Vec2(0.0, 0.0), reach).isModelled)
    }

    @Test
    fun `a wall counts as modelled when either of its corners was moved`() {
        val snapped = room.copy(snappedCorners = setOf(1))
        // Wall 0 runs from corner 0 to corner 1, so it inherits corner 1's snap.
        assertTrue(PlanSnapper.snap(listOf(snapped), Vec2(2.0, 0.1), reach).isModelled)
        // Wall 2 runs from corner 2 to corner 3 and touches neither.
        assertFalse(PlanSnapper.snap(listOf(snapped), Vec2(2.0, 3.9), reach).isModelled)
    }

    // --- anchors surviving an edit ----------------------------------------------------

    @Test
    fun `an anchored end follows its wall when the room is re-solved`() {
        val anchor = PlanSnapper.snap(rooms, Vec2(2.0, 0.1), reach).anchor
        // The room is stretched: the wall the point was on is now 6 m long.
        val stretched = room.copy(
            outline = listOf(Vec2(0.0, 0.0), Vec2(6.0, 0.0), Vec2(6.0, 4.0), Vec2(0.0, 4.0)),
        )

        val moved = PlanSnapper.resolve(listOf(stretched), anchor)!!
        // Still 40% along the wall, which is what "on that wall, there" means.
        assertVec(Vec2(2.4, 0.0), moved.position)
    }

    @Test
    fun `a free end does not move when the room does`() {
        val anchor = PlanSnapper.snap(rooms, Vec2(2.5, 2.0), reach).anchor
        val stretched = room.copy(
            outline = listOf(Vec2(0.0, 0.0), Vec2(9.0, 0.0), Vec2(9.0, 4.0), Vec2(0.0, 4.0)),
        )
        assertVec(Vec2(2.5, 2.0), PlanSnapper.resolve(listOf(stretched), anchor)!!.position)
    }

    @Test
    fun `an anchor to a room that has gone resolves to nothing`() {
        assertNull(PlanSnapper.resolve(emptyList(), PlanAnchor.Corner(1, 0)))
        assertNull(PlanSnapper.resolve(emptyList(), PlanAnchor.Wall(1, 0, 0.5)))
        // A corner index past the end of a re-solved room is the same situation.
        val triangle = room.copy(outline = room.outline.take(3))
        assertNull(PlanSnapper.resolve(listOf(triangle), PlanAnchor.Corner(1, 3)))
    }

    // --- the measurement itself -------------------------------------------------------

    @Test
    fun `a diagonal across the room reads its true length`() {
        val measurement = PlanMeasurement(
            from = PlanSnapper.resolve(rooms, PlanAnchor.Corner(1, 0))!!,
            to = PlanSnapper.resolve(rooms, PlanAnchor.Corner(1, 2))!!,
        )
        assertEquals(6.4031, measurement.length, 1e-4)
        assertFalse(measurement.isDegenerate)
    }

    @Test
    fun `the tolerance is not reduced for two corners of the same room`() {
        val measurement = PlanMeasurement(
            from = PlanSnapper.resolve(rooms, PlanAnchor.Corner(1, 0))!!,
            to = PlanSnapper.resolve(rooms, PlanAnchor.Corner(1, 2))!!,
        )
        // hypot(0.010, 0.014). The AR path cancels correlated error because it knows the
        // correlation; here the ends may come from separate solves, so it does not.
        assertEquals(0.0172, measurement.sigma, 1e-4)
    }

    @Test
    fun `two taps in the same place are one tap, not a measurement`() {
        val point = PlanSnapper.snap(rooms, Vec2(2.5, 2.0), reach)
        assertTrue(PlanMeasurement(point, point).isDegenerate)
    }

    @Test
    fun `a measurement to a modelled corner is itself modelled`() {
        val snapped = listOf(room.copy(snappedCorners = setOf(2)))
        val measurement = PlanMeasurement(
            from = PlanSnapper.resolve(snapped, PlanAnchor.Corner(1, 0))!!,
            to = PlanSnapper.resolve(snapped, PlanAnchor.Corner(1, 2))!!,
        )
        assertTrue(measurement.isModelled)
    }
}
