package com.measure.core.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SegmentsTest {

    private val a = Vec2(0.0, 0.0)
    private val b = Vec2(4.0, 0.0)

    /** A 5 x 4 m room, anticlockwise from the origin. */
    private val room = Polygon(
        listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 4.0), Vec2(0.0, 4.0)),
    )

    @Test
    fun `the nearest point falls where the perpendicular lands`() {
        assertEquals(Vec2(2.0, 0.0), Segments.nearestPointOn(a, b, Vec2(2.0, 3.0)))
        assertEquals(3.0, Segments.distanceToSegment(a, b, Vec2(2.0, 3.0)), 1e-12)
    }

    @Test
    fun `beyond the end it clamps to the end, not the infinite line`() {
        assertEquals(b, Segments.nearestPointOn(a, b, Vec2(9.0, 0.0)))
        assertEquals(5.0, Segments.distanceToSegment(a, b, Vec2(9.0, 0.0)), 1e-12)
        assertEquals(a, Segments.nearestPointOn(a, b, Vec2(-3.0, 0.0)))
    }

    @Test
    fun `a zero length segment does not divide by zero`() {
        assertEquals(a, Segments.nearestPointOn(a, a, Vec2(1.0, 1.0)))
    }

    @Test
    fun `the nearest edge is found, and only within reach`() {
        // Just inside the bottom wall.
        assertEquals(0, Segments.nearestEdge(room, Vec2(2.5, 0.1), 0.3))
        // The right-hand wall.
        assertEquals(1, Segments.nearestEdge(room, Vec2(4.9, 2.0), 0.3))
        // Dead centre of the room is near nothing.
        assertNull(Segments.nearestEdge(room, Vec2(2.5, 2.0), 0.3))
    }

    @Test
    fun `near a corner the tap resolves to one wall, not neither`() {
        val edge = Segments.nearestEdge(room, Vec2(0.05, 0.05), 0.3)
        assertTrue(edge == 0 || edge == 3, "expected a wall meeting the origin, got $edge")
    }

    @Test
    fun `the nearest vertex respects the reach limit`() {
        assertEquals(2, Segments.nearestVertex(room, Vec2(4.9, 3.9), 0.3))
        assertNull(Segments.nearestVertex(room, Vec2(2.5, 2.0), 0.3))
    }

    @Test
    fun `containment`() {
        assertTrue(Segments.contains(room, Vec2(2.5, 2.0)))
        assertFalse(Segments.contains(room, Vec2(-0.5, 2.0)))
        assertFalse(Segments.contains(room, Vec2(2.5, 9.0)))
    }

    @Test
    fun `containment survives a ray passing through a vertex`() {
        // Level with the corners at y = 4, where a naive test double-counts.
        assertFalse(Segments.contains(room, Vec2(-1.0, 4.0)))
        assertFalse(Segments.contains(room, Vec2(9.0, 0.0)))
    }

    @Test
    fun `a polygon with no area contains nothing`() {
        val collinear = Polygon(listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(2.0, 0.0)))
        assertFalse(Segments.contains(collinear, Vec2(1.0, 0.0)))
    }

    @Test
    fun `containment does not depend on winding order`() {
        val clockwise = Polygon(room.vertices.reversed())
        assertTrue(Segments.contains(clockwise, Vec2(2.5, 2.0)))
    }

    @Test
    fun `the inward normal points into the room`() {
        // Edge 0 is the bottom wall, so "in" is +y; edge 2 is the top wall, so "in" is -y.
        assertNormal(Vec2(0.0, 1.0), Segments.inwardNormal(room, 0))
        assertNormal(Vec2(0.0, -1.0), Segments.inwardNormal(room, 2))
        assertNormal(Vec2(-1.0, 0.0), Segments.inwardNormal(room, 1))
    }

    @Test
    fun `the inward normal ignores which way the room was walked`() {
        // The same physical wall, captured in the other direction. A door drawn from the
        // winding alone would swing out through it into nothing.
        val clockwise = Polygon(room.vertices.reversed())
        val bottomWall = clockwise.vertices.indices.single { index ->
            val from = clockwise.vertices[index]
            val to = clockwise.vertices[(index + 1) % clockwise.size]
            from.y == 0.0 && to.y == 0.0
        }
        assertNormal(Vec2(0.0, 1.0), Segments.inwardNormal(clockwise, bottomWall))
    }

    @Test
    fun `a shallow recess does not probe out the far side`() {
        // 2 m wide and 3 cm deep. A fixed 5 cm probe from the long wall would come out
        // the back and report the wrong side, taking a door's swing with it.
        val recess = Polygon(
            listOf(Vec2(0.0, 0.0), Vec2(2.0, 0.0), Vec2(2.0, 0.03), Vec2(0.0, 0.03)),
        )
        assertNormal(Vec2(0.0, 1.0), Segments.inwardNormal(recess, 0))
        assertNormal(Vec2(0.0, -1.0), Segments.inwardNormal(recess, 2))
    }

    /** Component-wise, because a perpendicular of an axis-aligned wall lands on -0.0. */
    private fun assertNormal(expected: Vec2, actual: Vec2?) {
        assertEquals(expected.x, actual?.x ?: Double.NaN, 1e-12)
        assertEquals(expected.y, actual?.y ?: Double.NaN, 1e-12)
    }

    @Test
    fun `a degenerate edge has no normal`() {
        val repeated = Polygon(listOf(Vec2(0.0, 0.0), Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(1.0, 1.0)))
        assertNull(Segments.inwardNormal(repeated, 0))
        assertNull(Segments.inwardNormal(room, 7))
    }
}
