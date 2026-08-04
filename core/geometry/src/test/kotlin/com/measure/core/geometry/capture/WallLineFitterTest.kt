package com.measure.core.geometry.capture

import com.measure.core.geometry.Vec2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

class WallLineFitterTest {

    /** Points sprayed over a wall running along +x at y = [offset], between two heights. */
    private fun wallSamples(
        offset: Double = 3.0,
        from: Double = 0.0,
        to: Double = 2.4,
        columns: Int = 7,
        rows: Int = 4,
        noise: Double = 0.0,
        seed: Int = 7,
    ): List<WallSample> {
        val random = Random(seed)
        return (0 until columns).flatMap { column ->
            (0 until rows).map { row ->
                val along = from + (to - from) * column / (columns - 1.0)
                WallSample(
                    plan = Vec2(along, offset + (random.nextDouble() - 0.5) * 2 * noise),
                    height = 0.4 + 1.6 * row / (rows - 1.0),
                )
            }
        }
    }

    @Test
    fun `a clean wall fits exactly`() {
        val line = WallLineFitter.fit(wallSamples())

        assertNotNull(line)
        // The wall runs along x, so its normal is along y.
        assertEquals(1.0, abs(line!!.direction.x), 1e-6)
        assertEquals(1.0, abs(line.normal.y), 1e-6)
        assertEquals(3.0, abs(line.normal dot line.origin), 1e-6)
        assertEquals(2.4, line.extent, 1e-6)
        assertEquals(1.6, line.verticalSpread, 1e-6)
        assertEquals(line.total, line.inliers)
        assertEquals(0.0, line.residual, 1e-9)
    }

    @Test
    fun `depth noise moves the line very little and is reported`() {
        val line = WallLineFitter.fit(wallSamples(noise = 0.02))!!

        // Twenty-eight noisy points still pin the wall to a few millimetres, which is the
        // entire reason for fitting a line rather than trusting one hit.
        assertEquals(3.0, abs(line.normal dot line.origin), 0.008)
        assertTrue(line.residual > 0.0, "a noisy fit must not claim to be exact")
        assertTrue(line.residual < 0.02, "residual ${line.residual} should be about the noise")
    }

    @Test
    fun `the floor in front of the wall does not drag the line`() {
        // Two thirds wall, one third floor stretching away from it in plan.
        val floor = (0 until 12).map {
            WallSample(plan = Vec2(0.2 * it, 1.0 + 0.1 * it), height = 0.0)
        }
        val line = WallLineFitter.fit(wallSamples() + floor)!!

        assertEquals(3.0, abs(line.normal dot line.origin), 0.01)
        assertTrue(line.inliers < line.total, "the floor points should have been rejected")
    }

    @Test
    fun `a floor on its own is not a wall`() {
        // Flat, spread over an area, and all at one height — the case the vertical spread
        // test exists for, since a floor seen edge-on can otherwise look line-shaped.
        val floor = (0 until 6).flatMap { row ->
            (0 until 6).map { column -> WallSample(Vec2(0.3 * column, 0.3 * row), height = 0.0) }
        }
        val line = WallLineFitter.fit(floor)
        // Either no line is agreed at all, or one is and it has no height to it.
        assertTrue(line == null || line.verticalSpread < WallLineFitter.MINIMUM_VERTICAL_SPREAD_METRES)
    }

    @Test
    fun `two walls meeting in view agree on neither`() {
        // Aimed into a corner, with half the points on each wall. Neither has a majority,
        // so the honest answer is that no single wall is in view — better than picking
        // one and letting the user take a wall they were not pointing at.
        val left = (0 until 14).map {
            WallSample(plan = Vec2(0.17 * it, 3.0), height = 0.4 + 0.1 * it)
        }
        val right = (0 until 14).map {
            WallSample(plan = Vec2(2.6, 3.2 + 0.17 * it), height = 0.4 + 0.1 * it)
        }
        assertNull(WallLineFitter.fit(left + right))
    }

    @Test
    fun `too few points is refused rather than guessed at`() {
        assertNull(WallLineFitter.fit(wallSamples(columns = 3, rows = 2)))
        assertNull(WallLineFitter.fit(emptyList()))
    }

    @Test
    fun `a wall running the other way fits just as well`() {
        // Ordinary regression would divide by zero here; the principal-axis fit does not.
        val northSouth = wallSamples().map { WallSample(Vec2(it.plan.y, it.plan.x), it.height) }
        val line = WallLineFitter.fit(northSouth)!!

        assertEquals(1.0, abs(line.direction.y), 1e-6)
        assertEquals(3.0, abs(line.normal dot line.origin), 1e-6)
    }

    @Test
    fun `a wall too short to be one is still fitted, and the caller decides`() {
        // The fitter reports extent; refusing on it is WallFace's job, so that the same
        // fit can be reused with different thresholds.
        val stub = wallSamples(from = 0.0, to = 0.3)
        val line = WallLineFitter.fit(stub)!!
        assertTrue(line.extent < WallFace.MINIMUM_EXTENT_METRES)
        assertNull(WallFace.fromFittedLine(1, line, range = 2.0))
    }

    @Test
    fun `a fitted wall never claims to be better than its own scatter`() {
        val line = WallLineFitter.fit(wallSamples(noise = 0.05))!!
        val face = WallFace.fromFittedLine(1, line, range = 1.0)!!

        assertTrue(face.sigma >= line.residual)
        assertTrue(face.sigma >= HitSource.WALL_DEPTH.sigmaAt(1.0))
        assertEquals(HitSource.WALL_DEPTH, face.source)
    }
}
