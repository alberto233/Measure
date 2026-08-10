package com.traza.core.geometry.plan

import com.traza.core.geometry.Vec2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs

class DimensionChainsTest {

    /** A 5 x 4 m room, axis aligned. */
    private val rectangle = listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 4.0), Vec2(0.0, 4.0))

    /** The same room turned by [radians] about the origin. */
    private fun rotated(outline: List<Vec2>, radians: Double) = outline.map { it.rotated(radians) }

    private fun assertVec(expected: Vec2, actual: Vec2, tolerance: Double = 1e-6) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
    }

    private fun assertAxis(expected: Vec2, actual: Vec2) {
        // A direction and its opposite describe the same axis, as do the two axes of one
        // grid; only the line matters.
        val alignment = abs(expected dot actual)
        assertEquals(1.0, alignment, 1e-6, "expected axis $expected, got $actual")
    }

    // --- which way the plan points -----------------------------------------------------

    @Test
    fun `an axis aligned room points along the axes`() {
        assertAxis(Vec2(1.0, 0.0), DimensionChains.dominantDirection(listOf(rectangle)))
    }

    @Test
    fun `a square room does not decide it points at forty five degrees`() {
        // The trap that averaging raw angles falls into: 0 and 90 average to 45.
        val square = listOf(Vec2(0.0, 0.0), Vec2(3.0, 0.0), Vec2(3.0, 3.0), Vec2(0.0, 3.0))
        assertAxis(Vec2(1.0, 0.0), DimensionChains.dominantDirection(listOf(square)))
    }

    @Test
    fun `a turned room is dimensioned along its own walls`() {
        val turned = rotated(rectangle, PI / 6)
        assertAxis(Vec2(1.0, 0.0).rotated(PI / 6), DimensionChains.dominantDirection(listOf(turned)))
    }

    @Test
    fun `the longest walls win when a room is not quite square`() {
        // A long room with one short skewed wall: the long walls set the direction.
        val skewed = listOf(
            Vec2(0.0, 0.0),
            Vec2(8.0, 0.0),
            Vec2(8.2, 1.0),
            Vec2(8.0, 3.0),
            Vec2(0.0, 3.0),
        )
        assertAxis(Vec2(1.0, 0.0), DimensionChains.dominantDirection(listOf(skewed)))
    }

    // --- the chains themselves ---------------------------------------------------------

    @Test
    fun `a plain rectangle gets one run each way`() {
        val chains = DimensionChains.chains(listOf(rectangle))
        assertEquals(2, chains.size)

        val along = chains[0]
        assertEquals(1, along.segments.size)
        assertEquals(5.0, along.overall, 1e-6)
        assertEquals(5.0, along.segments[0].length, 1e-6)

        val across = chains[1]
        assertEquals(1, across.segments.size)
        assertEquals(4.0, across.overall, 1e-6)
    }

    @Test
    fun `an L shape breaks its chain at the step`() {
        // 6 m wide overall, stepping in at 4 m.
        val ell = listOf(
            Vec2(0.0, 0.0),
            Vec2(6.0, 0.0),
            Vec2(6.0, 2.0),
            Vec2(4.0, 2.0),
            Vec2(4.0, 5.0),
            Vec2(0.0, 5.0),
        )
        val along = DimensionChains.chains(listOf(ell)).first()

        assertEquals(6.0, along.overall, 1e-6)
        assertEquals(listOf(4.0, 2.0), along.segments.map { it.length }.map { kotlin.math.round(it * 1e6) / 1e6 })
    }

    @Test
    fun `corners a centimetre apart are one tick, not a sliver`() {
        // What a real capture produces: a wall that should be flush is 8 mm out. Without
        // merging that would draw a run of 8 mm with a label wider than the room.
        val nearlyFlush = listOf(
            Vec2(0.0, 0.0),
            Vec2(5.0, 0.0),
            Vec2(5.008, 4.0),
            Vec2(0.0, 4.0),
        )
        val along = DimensionChains.chains(listOf(nearlyFlush)).first()
        assertEquals(1, along.segments.size)
        assertEquals(5.004, along.overall, 1e-6)
    }

    @Test
    fun `a turned room's chain runs parallel to its walls`() {
        val turned = rotated(rectangle, PI / 6)
        val along = DimensionChains.chains(listOf(turned)).first()

        assertEquals(5.0, along.overall, 1e-6)
        assertAxis(Vec2(1.0, 0.0).rotated(PI / 6), along.direction)
    }

    @Test
    fun `the chain sits on the near edge, so it can be drawn outside`() {
        val along = DimensionChains.chains(listOf(rectangle)).first()
        // Direction is +x, normal +y, and the room's lowest y is 0.
        assertEquals(0.0, along.baseline, 1e-6)
        assertVec(Vec2(0.0, 0.0), along.pointAt(along.ticks.first()))
        assertVec(Vec2(5.0, 0.0), along.pointAt(along.ticks.last()))
    }

    @Test
    fun `several rooms share one overall dimension`() {
        val second = rectangle.map { it + Vec2(5.0, 0.0) }
        val along = DimensionChains.chains(listOf(rectangle, second)).first()

        assertEquals(10.0, along.overall, 1e-6)
        // Ticks at 0, 5 and 10: the shared edge is one tick, not two.
        assertEquals(3, along.ticks.size)
    }

    @Test
    fun `there is nothing to dimension without a room`() {
        assertTrue(DimensionChains.chains(emptyList()).isEmpty())
        assertTrue(DimensionChains.chains(listOf(listOf(Vec2.ZERO, Vec2(1.0, 0.0)))).isEmpty())
    }
}
