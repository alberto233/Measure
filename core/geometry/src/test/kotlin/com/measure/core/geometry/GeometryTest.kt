package com.measure.core.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs

class Vec2Test {

    @Test
    fun `length and bearing`() {
        assertEquals(5.0, Vec2(3.0, 4.0).length, 1e-12)
        assertEquals(0.0, Vec2(1.0, 0.0).bearing, 1e-12)
        assertEquals(PI / 2, Vec2(0.0, 1.0).bearing, 1e-12)
    }

    @Test
    fun `cross product sign indicates turn direction`() {
        assertTrue((Vec2(1.0, 0.0) cross Vec2(0.0, 1.0)) > 0)
        assertTrue((Vec2(1.0, 0.0) cross Vec2(0.0, -1.0)) < 0)
    }

    @Test
    fun `rotation preserves length`() {
        val rotated = Vec2(3.0, 4.0).rotated(0.7)
        assertEquals(5.0, rotated.length, 1e-12)
    }

    @Test
    fun `perpendicular is a quarter turn anticlockwise`() {
        val p = Vec2(1.0, 0.0).perpendicular()
        assertEquals(0.0, p.x, 1e-12)
        assertEquals(1.0, p.y, 1e-12)
    }

    @Test
    fun `normalising a zero vector does not produce NaN`() {
        assertEquals(Vec2.ZERO, Vec2.ZERO.normalised())
    }
}

class PolygonTest {

    private val rectangle = Polygon(
        listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 4.0), Vec2(0.0, 4.0))
    )

    @Test
    fun `area and perimeter of a known rectangle`() {
        assertEquals(20.0, rectangle.area.squareMetres, 1e-9)
        assertEquals(18.0, rectangle.perimeter.metres, 1e-9)
    }

    @Test
    fun `area is orientation independent`() {
        val reversed = Polygon(rectangle.vertices.reversed())
        assertEquals(rectangle.area.squareMetres, reversed.area.squareMetres, 1e-9)
        assertTrue(reversed.isClockwise)
        assertFalse(rectangle.isClockwise)
    }

    @Test
    fun `centroid of a rectangle is its middle`() {
        assertEquals(2.5, rectangle.centroid.x, 1e-9)
        assertEquals(2.0, rectangle.centroid.y, 1e-9)
    }

    @Test
    fun `edges include the implicit closing edge`() {
        assertEquals(4, rectangle.edges.size)
        val closing = rectangle.edges.last()
        assertEquals(3, closing.fromIndex)
        assertEquals(0, closing.toIndex)
    }

    @Test
    fun `an L shaped room has the right area`() {
        // 4x4 square with a 2x2 bite taken out of one corner.
        val lShape = Polygon(
            listOf(
                Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(4.0, 2.0),
                Vec2(2.0, 2.0), Vec2(2.0, 4.0), Vec2(0.0, 4.0),
            )
        )
        assertEquals(12.0, lShape.area.squareMetres, 1e-9)
    }
}

class AngleSnapperTest {

    @Test
    fun `a rotated rectangle snaps on every edge`() {
        val rotation = 0.37 // an arbitrary room orientation in the AR frame
        val vertices = listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 4.0), Vec2(0.0, 4.0))
            .map { it.rotated(rotation) }

        val result = AngleSnapper().snap(Polygon(vertices))

        assertEquals(4, result.snappedCount)
        // The recovered axis is the room's orientation modulo 90 degrees.
        val axisError = normalisedDifference(result.axisBearing, rotation, PI / 2)
        assertTrue(abs(axisError) < 1e-6, "axis was ${result.axisBearing}, expected $rotation mod 90")
    }

    @Test
    fun `a small deviation is pulled square`() {
        // A wall 3 degrees off is well within tolerance and should snap.
        val vertices = listOf(
            Vec2(0.0, 0.0),
            Vec2(5.0, 0.26),
            Vec2(5.0, 4.0),
            Vec2(0.0, 4.0),
        )
        val result = AngleSnapper().snap(Polygon(vertices))
        assertTrue(result.snaps[0].isSnapped)
    }

    @Test
    fun `a genuine diagonal is left alone`() {
        // A 45 degree wall is 45 degrees from any right angle, far outside tolerance.
        val vertices = listOf(Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(0.0, 4.0))
        val result = AngleSnapper().snap(Polygon(vertices))
        val diagonal = result.snaps[1]
        assertFalse(diagonal.isSnapped, "a 45 degree wall must not be forced square")
    }

    @Test
    fun `diagonals snap when enabled`() {
        val vertices = listOf(Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(0.0, 4.0))
        val result = AngleSnapper(allowDiagonals = true).snap(Polygon(vertices))
        assertTrue(result.snaps[1].isSnapped)
    }

    @Test
    fun `the axis is dominated by long walls`() {
        // A long wall along x and a short wall skewed badly. The axis should follow the
        // long wall, because its bearing is the better measured of the two.
        val vertices = listOf(
            Vec2(0.0, 0.0),
            Vec2(10.0, 0.0),
            Vec2(9.7, 0.5),
            Vec2(0.0, 0.5),
        )
        val result = AngleSnapper().snap(Polygon(vertices))
        assertTrue(abs(result.axisBearing) < Math.toRadians(3.0))
    }

    private fun normalisedDifference(a: Double, b: Double, modulus: Double): Double {
        var d = (a - b) % modulus
        if (d > modulus / 2) d -= modulus
        if (d < -modulus / 2) d += modulus
        return d
    }
}

class LoopClosureTest {

    private val trueCorners = listOf(
        Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 4.0), Vec2(0.0, 4.0)
    )

    @Test
    fun `pure drift is very nearly removed`() {
        val drift = Vec2(0.12, -0.05)
        val perimeter = 18.0

        // Drift accumulates linearly with distance walked.
        var travelled = 0.0
        val observed = trueCorners.mapIndexed { index, corner ->
            if (index > 0) travelled += trueCorners[index].distanceTo(trueCorners[index - 1])
            corner + drift * (travelled / perimeter)
        }
        val closingObservation = trueCorners.first() + drift

        val result = LoopClosure.adjust(observed, closingObservation)

        // Not exact, and cannot be: the compass rule apportions by the *observed* leg
        // lengths, which are themselves slightly drifted. The residual is second order —
        // sub-millimetre against 13 cm of drift — which is far below the noise floor of
        // anything a phone camera can measure.
        for (i in trueCorners.indices) {
            assertEquals(trueCorners[i].x, result.adjusted[i].x, 1e-3)
            assertEquals(trueCorners[i].y, result.adjusted[i].y, 1e-3)
        }
    }

    @Test
    fun `the starting corner never moves`() {
        val observed = trueCorners
        val result = LoopClosure.adjust(observed, Vec2(0.3, 0.2))
        assertEquals(trueCorners[0], result.adjusted[0])
    }

    @Test
    fun `relative error is reported against the perimeter`() {
        // The traverse used for the ratio includes the closing leg as actually observed,
        // so the denominator is 18.004 m rather than a nominal 18 m.
        val result = LoopClosure.adjust(trueCorners, Vec2(0.18, 0.0))
        assertEquals(0.18 / 18.0, result.relativeError, 1e-4)
        assertTrue(result.isAcceptable)
    }

    @Test
    fun `a large misclosure is flagged rather than smoothed away`() {
        val result = LoopClosure.adjust(trueCorners, Vec2(2.7, 0.0)) // 15% of perimeter
        assertFalse(result.isAcceptable, "15% misclosure must not be silently accepted")
    }

    @Test
    fun `without a closing observation nothing is changed`() {
        val result = LoopClosure.adjust(trueCorners, null)
        assertEquals(trueCorners, result.adjusted)
        assertFalse(result.wasAdjusted)
    }
}
