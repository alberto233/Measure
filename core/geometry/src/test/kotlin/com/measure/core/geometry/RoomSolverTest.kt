package com.measure.core.geometry

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.abs

/**
 * The tests that justify the product.
 *
 * A synthetic room is corrupted the way a real capture is corrupted — per-corner
 * targeting noise plus tracking drift that accumulates as the user walks — and the
 * pipeline has to recover it to the tolerances promised in docs/ACCURACY.md:
 * each wall within 2%, area within 4%.
 *
 * This runs on the JVM in milliseconds and needs no phone, which is the whole reason
 * the geometry core is a pure-Kotlin module.
 */
class RoomSolverTest {

    private val rectangle = listOf(
        Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 4.0), Vec2(0.0, 4.0)
    )

    private val lShape = listOf(
        Vec2(0.0, 0.0), Vec2(6.0, 0.0), Vec2(6.0, 2.5),
        Vec2(3.0, 2.5), Vec2(3.0, 5.0), Vec2(0.0, 5.0),
    )

    @Test
    fun `a noisy drifting rectangle is recovered within tolerance`() {
        val truth = rectangle
        val capture = simulateCapture(truth, seed = 42, noiseSigma = 0.02, drift = Vec2(0.10, -0.06))

        val solution = RoomSolver.solve(capture)

        assertWallsWithin(truth, solution, fraction = 0.02)
        assertAreaWithin(truth, solution, fraction = 0.04)
        assertTrue(solution.isReliable, "a 2 cm / 12 cm capture should be considered reliable")
    }

    @Test
    fun `an L shaped room is recovered within tolerance`() {
        val truth = lShape
        val capture = simulateCapture(truth, seed = 7, noiseSigma = 0.02, drift = Vec2(-0.08, 0.11))

        val solution = RoomSolver.solve(capture)

        assertWallsWithin(truth, solution, fraction = 0.02)
        assertAreaWithin(truth, solution, fraction = 0.04)
    }

    @Test
    fun `the room orientation in the AR frame does not matter`() {
        val truth = rectangle.map { it.rotated(1.1) }
        val capture = simulateCapture(truth, seed = 99, noiseSigma = 0.02, drift = Vec2(0.09, 0.07))

        val solution = RoomSolver.solve(capture)

        assertWallsWithin(truth, solution, fraction = 0.02)
        assertAreaWithin(truth, solution, fraction = 0.04)
    }

    @Test
    fun `the pipeline beats the raw observations`() {
        // The claim under test: correction is worth doing. Averaged over many seeds the
        // solved plan must have materially less wall error than what came off the phone.
        var rawTotal = 0.0
        var solvedTotal = 0.0

        for (seed in 1..40) {
            val capture = simulateCapture(rectangle, seed.toLong(), noiseSigma = 0.025, drift = Vec2(0.12, -0.09))
            val raw = Polygon(capture.corners.map { it.position })
            val solved = RoomSolver.solve(capture).polygon

            rawTotal += meanWallError(rectangle, raw)
            solvedTotal += meanWallError(rectangle, solved)
        }

        assertTrue(
            solvedTotal < rawTotal * 0.6,
            "expected the solver to cut wall error by at least 40%, " +
                "raw=${"%.4f".format(rawTotal / 40)} solved=${"%.4f".format(solvedTotal / 40)}",
        )
    }

    @Test
    fun `right angles are restored`() {
        val capture = simulateCapture(rectangle, seed = 5, noiseSigma = 0.02, drift = Vec2(0.1, 0.05))
        val solution = RoomSolver.solve(capture)

        // Every corner of a solved rectangle should be square to within a fraction of a degree.
        val vertices = solution.polygon.vertices
        for (i in vertices.indices) {
            val previous = vertices[(i + vertices.size - 1) % vertices.size]
            val next = vertices[(i + 1) % vertices.size]
            val a = (previous - vertices[i]).normalised()
            val b = (next - vertices[i]).normalised()
            val degrees = Math.toDegrees(kotlin.math.acos((a dot b).coerceIn(-1.0, 1.0)))
            assertTrue(abs(degrees - 90.0) < 1.0, "corner $i was $degrees degrees")
        }
    }

    @Test
    fun `a locked wall length pulls the whole plan`() {
        // The editor feature: tape measure one wall, type it, watch the plan tighten.
        val capture = simulateCapture(rectangle, seed = 11, noiseSigma = 0.05, drift = Vec2(0.15, 0.1))

        val locked = listOf(LengthConstraint(fromIndex = 0, toIndex = 1, length = 5.0))
        val solution = RoomSolver.solve(capture, lockedLengths = locked)

        val wall = solution.polygon.edges[0].length
        assertTrue(abs(wall - 5.0) < 0.005, "locked wall should sit on 5.000 m, was $wall")
    }

    @Test
    fun `snapping can be turned off for an irregular room`() {
        // A genuinely non-rectilinear room must not be forced square.
        val trapezium = listOf(
            Vec2(0.0, 0.0), Vec2(6.0, 0.0), Vec2(5.0, 3.0), Vec2(1.0, 3.0)
        )
        val capture = simulateCapture(trapezium, seed = 3, noiseSigma = 0.01, drift = Vec2.ZERO)

        val solution = RoomSolver.solve(capture, RoomSolverOptions(snapEnabled = false))

        assertWallsWithin(trapezium, solution, fraction = 0.03)
    }

    @Test
    fun `a capture that does not close is still solved`() {
        val capture = RoomCapture(
            corners = rectangle.map { CapturedCorner(it, sigma = 0.02) },
            closingObservation = null,
        )
        val solution = RoomSolver.solve(capture)
        assertWallsWithin(rectangle, solution, fraction = 0.02)
    }

    // --- helpers -----------------------------------------------------------------

    /**
     * Corrupts a true room the way a real walk-around corrupts it: independent targeting
     * noise at each corner, plus drift that grows with distance travelled and shows up
     * as a misclosure when the user re-taps the corner they started on.
     */
    private fun simulateCapture(
        truth: List<Vec2>,
        seed: Long,
        noiseSigma: Double,
        drift: Vec2,
    ): RoomCapture {
        val random = Random(seed)

        val perimeter = truth.indices.sumOf { i ->
            truth[i].distanceTo(truth[(i + 1) % truth.size])
        }

        var travelled = 0.0
        val corners = truth.mapIndexed { index, corner ->
            if (index > 0) travelled += truth[index].distanceTo(truth[index - 1])
            val drifted = corner + drift * (travelled / perimeter)
            val noisy = drifted + Vec2(
                random.nextGaussian() * noiseSigma,
                random.nextGaussian() * noiseSigma,
            )
            CapturedCorner(noisy, sigma = noiseSigma)
        }

        val closing = truth.first() + drift + Vec2(
            random.nextGaussian() * noiseSigma,
            random.nextGaussian() * noiseSigma,
        )

        return RoomCapture(corners, closing)
    }

    private fun meanWallError(truth: List<Vec2>, actual: Polygon): Double {
        val truePolygon = Polygon(truth)
        return truePolygon.edges.indices.sumOf { i ->
            abs(actual.edges[i].length - truePolygon.edges[i].length)
        } / truth.size
    }

    private fun assertWallsWithin(truth: List<Vec2>, solution: RoomSolution, fraction: Double) {
        val truePolygon = Polygon(truth)
        for (i in truePolygon.edges.indices) {
            val expected = truePolygon.edges[i].length
            val actual = solution.polygon.edges[i].length
            val error = abs(actual - expected) / expected
            assertTrue(
                error <= fraction,
                "wall $i was ${"%.3f".format(actual)} m, expected ${"%.3f".format(expected)} m " +
                    "(${"%.1f".format(error * 100)}% error, limit ${fraction * 100}%)",
            )
        }
    }

    private fun assertAreaWithin(truth: List<Vec2>, solution: RoomSolution, fraction: Double) {
        val expected = Polygon(truth).area.squareMetres
        val actual = solution.area.squareMetres
        val error = abs(actual - expected) / expected
        assertTrue(
            error <= fraction,
            "area was ${"%.2f".format(actual)} m², expected ${"%.2f".format(expected)} m² " +
                "(${"%.1f".format(error * 100)}% error, limit ${fraction * 100}%)",
        )
    }
}
