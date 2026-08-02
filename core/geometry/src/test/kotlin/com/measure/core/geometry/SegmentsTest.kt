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
}
