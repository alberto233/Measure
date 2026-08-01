package com.measure.core.geometry.capture

import com.measure.core.geometry.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

private fun sample(
    x: Double,
    y: Double,
    z: Double,
    range: Double = 2.0,
    source: HitSource = HitSource.PLANE_POLYGON,
    quality: TrackingQuality = TrackingQuality.GOOD,
) = PointSample(Vec3(x, y, z), range, source, quality)

/** A steady burst around [centre] with millimetre jitter. */
private fun steadyBurst(
    centre: Vec3,
    count: Int = 15,
    jitter: Double = 0.002,
    seed: Int = 7,
    source: HitSource = HitSource.PLANE_POLYGON,
    quality: TrackingQuality = TrackingQuality.GOOD,
): List<PointSample> {
    val random = Random(seed)
    return List(count) {
        sample(
            centre.x + random.nextDouble(-jitter, jitter),
            centre.y + random.nextDouble(-jitter, jitter),
            centre.z + random.nextDouble(-jitter, jitter),
            source = source,
            quality = quality,
        )
    }
}

class Vec3Test {

    @Test
    fun `length and distance`() {
        assertEquals(5.0, Vec3(3.0, 4.0, 0.0).length, 1e-12)
        assertEquals(13.0, Vec3.ZERO.distanceTo(Vec3(3.0, 4.0, 12.0)), 1e-12)
    }

    @Test
    fun `horizontal distance ignores height, vertical distance is signed`() {
        val floor = Vec3(0.0, 0.0, 0.0)
        val ceiling = Vec3(0.0, 2.4, 0.0)
        assertEquals(0.0, floor.horizontalDistanceTo(ceiling), 1e-12)
        assertEquals(2.4, floor.verticalDistanceTo(ceiling), 1e-12)
        assertEquals(-2.4, ceiling.verticalDistanceTo(floor), 1e-12)
    }

    @Test
    fun `cross product follows the right-hand rule`() {
        val c = Vec3(1.0, 0.0, 0.0) cross Vec3(0.0, 1.0, 0.0)
        assertEquals(Vec3(0.0, 0.0, 1.0), c)
    }

    @Test
    fun `normalising a zero vector does not produce NaN`() {
        assertEquals(Vec3.ZERO, Vec3.ZERO.normalised())
    }

    @Test
    fun `dropping to the floor plane discards height`() {
        val plan = Vec3(1.5, 9.9, -2.0).toFloorPlane()
        assertEquals(1.5, plan.x, 1e-12)
        assertEquals(2.0, plan.y, 1e-12)
    }
}

class HitSourceTest {

    @Test
    fun `sources are declared best first`() {
        val ordered = HitSource.entries.toList()
        assertEquals(HitSource.PLANE_POLYGON, ordered.first())
        assertEquals(HitSource.INSTANT_PLACEMENT, ordered.last())
    }

    @Test
    fun `a better ranked source is never less certain at any range`() {
        for (range in listOf(0.5, 1.0, 2.0, 4.0, 8.0)) {
            val sigmas = HitSource.entries.map { it.sigmaAt(range) }
            assertEquals(sigmas.sorted(), sigmas, "ranking disagrees with sigma at $range m")
        }
    }

    @Test
    fun `only a hit inside a plane polygon counts as structural`() {
        assertTrue(HitSource.PLANE_POLYGON.isStructural)
        assertFalse(HitSource.PLANE_INFINITE.isStructural)
        assertFalse(HitSource.DEPTH.isStructural)
    }
}

class TrackingAssessorTest {

    @Test
    fun `not tracking is never capturable`() {
        val status = TrackingAssessor.assess(
            isTracking = false,
            reportedIssue = TrackingIssue.INSUFFICIENT_LIGHT,
            trackedPlaneCount = 4,
            featurePointCount = 500,
        )
        assertEquals(TrackingQuality.NONE, status.quality)
        assertEquals(TrackingIssue.INSUFFICIENT_LIGHT, status.issue)
        assertFalse(status.canCapture)
    }

    @Test
    fun `a reported failure reason overrides a healthy-looking map`() {
        val status = TrackingAssessor.assess(
            isTracking = true,
            reportedIssue = TrackingIssue.EXCESSIVE_MOTION,
            trackedPlaneCount = 6,
            featurePointCount = 900,
        )
        assertEquals(TrackingQuality.POOR, status.quality)
        assertEquals(TrackingIssue.EXCESSIVE_MOTION, status.issue)
        assertFalse(status.canCapture)
    }

    @Test
    fun `a sparse map is poor even when ARCore claims to be tracking`() {
        val status = TrackingAssessor.assess(true, TrackingIssue.NONE, 2, 10)
        assertEquals(TrackingQuality.POOR, status.quality)
        assertEquals(TrackingIssue.INSUFFICIENT_FEATURES, status.issue)
    }

    @Test
    fun `no planes yet is fair and says so`() {
        val status = TrackingAssessor.assess(true, TrackingIssue.NONE, 0, 400)
        assertEquals(TrackingQuality.FAIR, status.quality)
        assertEquals(TrackingIssue.NO_SURFACES_YET, status.issue)
        assertTrue(status.canCapture)
    }

    @Test
    fun `a dense map with planes is good`() {
        val status = TrackingAssessor.assess(true, TrackingIssue.NONE, 3, 400)
        assertEquals(TrackingQuality.GOOD, status.quality)
        assertEquals(TrackingIssue.NONE, status.issue)
        assertTrue(status.canCapture)
    }

    @Test
    fun `every issue carries advice the user can act on`() {
        TrackingIssue.entries.forEach { assertTrue(it.advice.isNotBlank(), "$it has no advice") }
    }
}

class RangeGateTest {

    @Test
    fun `the comfortable band is silent and everything else speaks`() {
        assertEquals(RangeAdvice.TOO_CLOSE, RangeGate.advise(0.15))
        assertEquals(RangeAdvice.IDEAL, RangeGate.advise(2.0))
        assertEquals(RangeAdvice.LONG, RangeGate.advise(6.0))
        assertEquals(RangeAdvice.VERY_LONG, RangeGate.advise(12.0))

        assertEquals(null, RangeAdvice.IDEAL.message)
        RangeAdvice.entries.filter { it != RangeAdvice.IDEAL }
            .forEach { assertNotNull(it.message, "$it should warn") }
    }

    @Test
    fun `boundaries fall on the friendlier side`() {
        assertEquals(RangeAdvice.IDEAL, RangeGate.advise(RangeGate.MINIMUM_METRES))
        assertEquals(RangeAdvice.IDEAL, RangeGate.advise(RangeGate.COMFORTABLE_METRES))
        assertEquals(RangeAdvice.LONG, RangeGate.advise(RangeGate.LONG_METRES))
    }
}

class PointAggregatorTest {

    @Test
    fun `an empty burst means there was nothing to aim at`() {
        val outcome = PointAggregator.aggregate(emptyList())
        assertEquals(CaptureOutcome.Rejected(CaptureRejection.NO_SURFACE), outcome)
    }

    @Test
    fun `a burst that was entirely untracked is rejected as tracking loss`() {
        val burst = steadyBurst(Vec3(1.0, 0.0, -2.0), quality = TrackingQuality.POOR)
        val outcome = PointAggregator.aggregate(burst)
        assertEquals(CaptureRejection.TRACKING_LOST, (outcome as CaptureOutcome.Rejected).reason)
    }

    @Test
    fun `too short a burst is rejected rather than averaged`() {
        val burst = steadyBurst(Vec3(1.0, 0.0, -2.0), count = 3)
        val outcome = PointAggregator.aggregate(burst)
        assertEquals(CaptureRejection.TOO_FEW_SAMPLES, (outcome as CaptureOutcome.Rejected).reason)
    }

    @Test
    fun `a steady burst is accepted close to its centre`() {
        val centre = Vec3(1.0, 0.5, -2.0)
        val outcome = PointAggregator.aggregate(steadyBurst(centre))
        val point = assertInstanceOf(CaptureOutcome.Accepted::class.java, outcome).point

        assertTrue(point.position.distanceTo(centre) < 0.002, "median drifted: ${point.position}")
        assertEquals(15, point.sampleCount)
        assertTrue(point.dispersion < 0.005, "dispersion ${point.dispersion}")
    }

    @Test
    fun `the median absorbs a wild outlier that a mean would not`() {
        val centre = Vec3(1.0, 0.5, -2.0)
        // Two frames where the reticle crossed an edge and hit the far wall.
        val burst = steadyBurst(centre) + listOf(
            sample(1.0, 0.5, -8.0, range = 8.0),
            sample(1.0, 0.5, -8.2, range = 8.2),
        )
        val point = assertInstanceOf(
            CaptureOutcome.Accepted::class.java,
            PointAggregator.aggregate(burst),
        ).point

        assertTrue(
            point.position.distanceTo(centre) < 0.01,
            "outliers dragged the point to ${point.position}",
        )
        // The mean of the same data is dragged nearly 80 cm off.
        val meanZ = burst.map { it.position.z }.average()
        assertTrue(abs(meanZ - centre.z) > 0.5, "the test data does not actually challenge a mean")
    }

    @Test
    fun `a wobbling burst is refused, and says how far it wobbled`() {
        val burst = steadyBurst(Vec3(1.0, 0.5, -2.0), jitter = 0.12)
        val rejection = assertInstanceOf(
            CaptureOutcome.Rejected::class.java,
            PointAggregator.aggregate(burst),
        )
        assertEquals(CaptureRejection.UNSTABLE, rejection.reason)
        assertTrue(rejection.dispersion > SamplingConfig().maxDispersion)
    }

    @Test
    fun `frames where tracking degraded are discarded, not averaged in`() {
        val centre = Vec3(1.0, 0.5, -2.0)
        val burst = steadyBurst(centre) +
            List(8) { sample(1.0, 0.5, -5.0, quality = TrackingQuality.POOR) }

        val point = assertInstanceOf(
            CaptureOutcome.Accepted::class.java,
            PointAggregator.aggregate(burst),
        ).point

        assertEquals(15, point.sampleCount, "untracked frames leaked into the median")
        assertTrue(point.position.distanceTo(centre) < 0.01)
    }

    @Test
    fun `a point closer than the gate is refused`() {
        val burst = steadyBurst(Vec3(0.0, 0.0, -0.2)).map { it.copy(range = 0.2) }
        val rejection = assertInstanceOf(
            CaptureOutcome.Rejected::class.java,
            PointAggregator.aggregate(burst),
        )
        assertEquals(CaptureRejection.TOO_CLOSE, rejection.reason)
    }

    @Test
    fun `the reported source is the worst that contributed, not the best`() {
        val centre = Vec3(1.0, 0.5, -2.0)
        val burst = steadyBurst(centre, count = 10) +
            steadyBurst(centre, count = 5, seed = 3, source = HitSource.DEPTH)

        val point = assertInstanceOf(
            CaptureOutcome.Accepted::class.java,
            PointAggregator.aggregate(burst),
        ).point
        assertSame(HitSource.DEPTH, point.source)
    }

    @Test
    fun `median handles even-sized lists`() {
        assertEquals(2.5, PointAggregator.median(listOf(4.0, 1.0, 3.0, 2.0)), 1e-12)
        assertEquals(3.0, PointAggregator.median(listOf(5.0, 1.0, 3.0)), 1e-12)
    }
}

class PointUncertaintyTest {

    @Test
    fun `sigma is never below the surface model, however steady the hand`() {
        val sigma = PointUncertainty.sigma(
            dispersion = 0.0,
            range = 2.0,
            source = HitSource.PLANE_POLYGON,
            quality = TrackingQuality.GOOD,
        )
        assertEquals(HitSource.PLANE_POLYGON.sigmaAt(2.0), sigma, 1e-12)
    }

    @Test
    fun `sigma grows with range, with a worse source, and with worse tracking`() {
        val baseline = PointUncertainty.sigma(0.005, 2.0, HitSource.PLANE_POLYGON, TrackingQuality.GOOD)

        assertTrue(
            PointUncertainty.sigma(0.005, 6.0, HitSource.PLANE_POLYGON, TrackingQuality.GOOD) > baseline,
        )
        assertTrue(
            PointUncertainty.sigma(0.005, 2.0, HitSource.DEPTH, TrackingQuality.GOOD) > baseline,
        )
        assertTrue(
            PointUncertainty.sigma(0.005, 2.0, HitSource.PLANE_POLYGON, TrackingQuality.POOR) > baseline,
        )
    }

    @Test
    fun `a plane hit at two metres is inside the two centimetre claim`() {
        // docs/ACCURACY.md §3 promises 1-2% at 1-4 m on textured surfaces.
        val sigma = PointUncertainty.sigma(0.004, 2.0, HitSource.PLANE_POLYGON, TrackingQuality.GOOD)
        assertTrue(sigma < 0.02, "sigma $sigma exceeds the published expectation")
    }

    @Test
    fun `distance sigma combines both endpoints`() {
        val point = SampledPoint(Vec3.ZERO, sigma = 0.03, dispersion = 0.0, range = 2.0, source = HitSource.PLANE_POLYGON, sampleCount = 15)
        assertEquals(0.03 * kotlin.math.sqrt(2.0), PointUncertainty.distanceSigma(point, point), 1e-12)
    }
}

class MeasurementModeTest {

    private val anchor = Vec3(1.0, 0.0, -2.0)

    @Test
    fun `free mode changes nothing`() {
        val candidate = Vec3(3.0, 1.4, -5.0)
        val result = MeasurementMode.FREE.constrain(anchor, candidate)
        assertEquals(candidate, result.position)
        assertEquals(0.0, result.correction, 1e-12)
    }

    @Test
    fun `level mode pulls the second point to the first point's height`() {
        val candidate = Vec3(3.0, 0.11, -2.0)
        val result = MeasurementMode.HORIZONTAL.constrain(anchor, candidate)

        assertEquals(anchor.y, result.position.y, 1e-12)
        assertEquals(3.0, result.position.x, 1e-12)
        assertEquals(0.11, result.correction, 1e-12)
        assertEquals(2.0, anchor.distanceTo(result.position), 1e-12)
    }

    @Test
    fun `plumb mode measures pure height`() {
        val candidate = Vec3(1.06, 2.4, -2.08)
        val result = MeasurementMode.VERTICAL.constrain(anchor, candidate)

        assertEquals(anchor.x, result.position.x, 1e-12)
        assertEquals(anchor.z, result.position.z, 1e-12)
        assertEquals(2.4, anchor.distanceTo(result.position), 1e-12)
        assertEquals(0.1, result.correction, 1e-9)
    }

    @Test
    fun `a large correction is flagged rather than applied silently`() {
        val far = Vec3(1.0, 1.0, -2.0)
        assertTrue(MeasurementMode.HORIZONTAL.constrain(anchor, far).isNotable)
        assertFalse(MeasurementMode.HORIZONTAL.constrain(anchor, Vec3(1.0, 0.02, -2.0)).isNotable)
    }

    @Test
    fun `every mode has a label and a hint`() {
        MeasurementMode.entries.forEach {
            assertTrue(it.label.isNotBlank())
            assertTrue(it.hint.isNotBlank())
        }
    }
}

class MeasuredSegmentTest {

    private fun point(x: Double, y: Double, z: Double, sigma: Double = 0.01) = SampledPoint(
        position = Vec3(x, y, z),
        sigma = sigma,
        dispersion = 0.003,
        range = 2.0,
        source = HitSource.PLANE_POLYGON,
        sampleCount = 15,
    )

    @Test
    fun `length, sigma and midpoint`() {
        val segment = MeasuredSegment(1L, point(0.0, 0.0, 0.0), point(3.0, 0.0, 4.0), MeasurementMode.FREE)

        assertEquals(5.0, segment.lengthMetres, 1e-12)
        assertEquals(0.01 * kotlin.math.sqrt(2.0), segment.sigmaMetres, 1e-12)
        assertEquals(Vec3(1.5, 0.0, 2.0), segment.midpoint)
    }

    @Test
    fun `a well-sampled segment meets the published accuracy target`() {
        val segment = MeasuredSegment(1L, point(0.0, 0.0, 0.0), point(3.0, 0.0, 0.0), MeasurementMode.FREE)
        assertTrue(segment.meetsAccuracyTarget)
        assertTrue(segment.relativeSigma < 0.02)
    }

    @Test
    fun `a noisy long segment fails the target`() {
        val segment = MeasuredSegment(
            1L,
            point(0.0, 0.0, 0.0, sigma = 0.15),
            point(3.0, 0.0, 0.0, sigma = 0.15),
            MeasurementMode.FREE,
        )
        assertFalse(segment.meetsAccuracyTarget)
    }

    @Test
    fun `a degenerate segment does not divide by zero`() {
        val segment = MeasuredSegment(1L, point(0.0, 0.0, 0.0), point(0.0, 0.0, 0.0), MeasurementMode.FREE)
        assertTrue(segment.relativeSigma.isInfinite())
    }
}
