package com.measure.core.geometry.capture

import com.measure.core.geometry.Vec3
import kotlin.math.exp
import kotlin.math.sqrt

/** One frame's observation of where the reticle is pointing. */
data class PointSample(
    val position: Vec3,
    /** Distance from the camera to the hit, in metres. */
    val range: Double,
    val source: HitSource,
    val quality: TrackingQuality,
)

/** The result of collapsing a burst of [PointSample]s into one point. */
data class SampledPoint(
    val position: Vec3,
    /** Total standard deviation of this point's position, in metres. */
    val sigma: Double,
    /** Spread of the raw samples alone, in metres. Diagnostic, and feeds [sigma]. */
    val dispersion: Double,
    val range: Double,
    /** The worst source that contributed, so the reported quality is not flattering. */
    val source: HitSource,
    val sampleCount: Int,
)

enum class CaptureRejection(val message: String) {
    NO_SURFACE("Nothing to measure — aim at a surface"),
    TRACKING_LOST("Tracking dropped out — hold steadier and try again"),
    TOO_FEW_SAMPLES("Hold still a moment longer"),
    UNSTABLE("Too much wobble — hold steadier or step closer"),
    TOO_CLOSE("Too close to measure — step back"),
}

sealed interface CaptureOutcome {
    data class Accepted(val point: SampledPoint) : CaptureOutcome
    data class Rejected(val reason: CaptureRejection, val dispersion: Double = 0.0) : CaptureOutcome
}

data class SamplingConfig(
    /** Roughly half a second at 30 fps (docs/ACCURACY.md M3). */
    val targetFrames: Int = 15,
    /** Fewer usable frames than this and the burst is not worth a median. */
    val minAcceptedSamples: Int = 6,
    /** Above this spread the user was not actually holding still. */
    val maxDispersion: Double = 0.03,
    /** Frames below this tracking quality are discarded rather than averaged in. */
    val minSampleQuality: TrackingQuality = TrackingQuality.FAIR,
)

/**
 * Multi-frame median sampling — docs/ACCURACY.md M3.
 *
 * Two things make this worth doing over simply taking the tapped frame. First, the
 * **median** is robust: the characteristic failure is one or two wild frames where the
 * reticle crossed an edge and the hit jumped to the far wall, and a mean would drag the
 * result halfway there. Second, the spread of the surviving samples is a per-point
 * uncertainty estimate that costs nothing extra to compute, and it is the number that
 * feeds both the "±3 cm" display and the constraint solver's weights.
 */
object PointAggregator {

    /**
     * Scale factor converting a median absolute deviation into a standard deviation for
     * normally distributed data. The textbook constant; it is what makes the robust
     * spread comparable with the sigmas in [HitSource].
     */
    private const val MAD_TO_SIGMA = 1.4826

    fun aggregate(
        samples: List<PointSample>,
        config: SamplingConfig = SamplingConfig(),
    ): CaptureOutcome {
        if (samples.isEmpty()) return CaptureOutcome.Rejected(CaptureRejection.NO_SURFACE)

        val usable = samples.filter { it.quality >= config.minSampleQuality }
        if (usable.isEmpty()) return CaptureOutcome.Rejected(CaptureRejection.TRACKING_LOST)
        if (usable.size < config.minAcceptedSamples) {
            return CaptureOutcome.Rejected(CaptureRejection.TOO_FEW_SAMPLES)
        }

        // Componentwise median. Not the geometric median, which has no closed form and
        // would need iterating; componentwise is robust enough for a tight cluster of
        // samples plus the occasional outlier, which is exactly the distribution here.
        val centre = Vec3(
            median(usable.map { it.position.x }),
            median(usable.map { it.position.y }),
            median(usable.map { it.position.z }),
        )

        val dispersion = MAD_TO_SIGMA * median(usable.map { it.position.distanceTo(centre) })
        if (dispersion > config.maxDispersion) {
            return CaptureOutcome.Rejected(CaptureRejection.UNSTABLE, dispersion)
        }

        val range = median(usable.map { it.range })
        if (range < RangeGate.MINIMUM_METRES) {
            return CaptureOutcome.Rejected(CaptureRejection.TOO_CLOSE, dispersion)
        }

        // Report the worst contributing source and the worst tracking quality seen. If
        // half the burst fell back to depth, the point is a depth point.
        val source = usable.maxOf { it.source }
        val quality = usable.minOf { it.quality }

        return CaptureOutcome.Accepted(
            SampledPoint(
                position = centre,
                sigma = PointUncertainty.sigma(dispersion, range, source, quality),
                dispersion = dispersion,
                range = range,
                source = source,
                sampleCount = usable.size,
            ),
        )
    }

    /** Median of a non-empty list, averaging the middle pair when even. */
    internal fun median(values: List<Double>): Double {
        require(values.isNotEmpty()) { "median of an empty list" }
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }
}

/**
 * The error model — docs/ACCURACY.md M12.
 *
 * Every number this app shows carries a sigma, and this is where it comes from. Two
 * independent contributions, combined in quadrature because they are uncorrelated:
 * the *measured* spread of the sample burst (targeting error, hand shake), and the
 * *modelled* error of the surface estimate itself at that range, which sampling cannot
 * see because every frame in the burst shares it.
 */
object PointUncertainty {

    /** Degraded tracking inflates the modelled term; it cannot inflate a measurement. */
    private fun qualityFactor(quality: TrackingQuality): Double = when (quality) {
        TrackingQuality.GOOD -> 1.0
        TrackingQuality.FAIR -> 1.6
        TrackingQuality.POOR -> 2.5
        TrackingQuality.NONE -> 4.0
    }

    fun sigma(
        dispersion: Double,
        range: Double,
        source: HitSource,
        quality: TrackingQuality,
    ): Double {
        val modelled = source.sigmaAt(range) * qualityFactor(quality)
        return sqrt(dispersion * dispersion + modelled * modelled)
    }

    /**
     * Uncertainty of the distance between two sampled points.
     *
     * The obvious formula is the quadrature sum, treating the two errors as independent.
     * That is wrong in a way that matters, and it showed up the first time a real
     * measurement was taken: two points a third of a metre apart on the same rug came
     * back at ±3 cm, or 8% of the distance, when the number was in fact good to a
     * centimetre.
     *
     * The reason is that **a measurement is a difference, and common-mode error cancels
     * out of a difference.** If ARCore's floor plane sits a centimetre below the real
     * floor, both endpoints are a centimetre low and the distance between them is
     * untouched. So the shared part of the error must be subtracted, not added:
     *
     *     Var(a − b) = σa² + σb² − 2ρ·σa·σb
     *
     * Only the *modelled* surface error is shared. The sampling dispersion is targeting
     * error — hand shake, the reticle not quite on the mark — which is independent
     * between two separate taps and stays in the sum at full weight.
     */
    fun distanceSigma(from: SampledPoint, to: SampledPoint): Double {
        val modelledFrom = modelledPart(from)
        val modelledTo = modelledPart(to)
        val shared = correlation(from, to)

        val variance = from.dispersion * from.dispersion +
            to.dispersion * to.dispersion +
            modelledFrom * modelledFrom +
            modelledTo * modelledTo -
            2.0 * shared * modelledFrom * modelledTo

        return sqrt(variance.coerceAtLeast(MINIMUM_SIGMA * MINIMUM_SIGMA))
    }

    /** The part of a point's sigma that came from the surface model rather than the burst. */
    private fun modelledPart(point: SampledPoint): Double =
        sqrt((point.sigma * point.sigma - point.dispersion * point.dispersion).coerceAtLeast(0.0))

    /**
     * How much of two points' modelled error is shared.
     *
     * Zero when the points came from different kinds of estimate, since they have no
     * common fit to share. Otherwise it decays with separation: two points a handspan
     * apart are almost certainly on the same fitted plane, while two points across a
     * room may well be on different ones, and by then the cancellation should be assumed
     * gone. Nothing here can exceed the source's own ceiling, so a distance is never
     * reported as more certain than the surface it was measured from allows.
     */
    private fun correlation(from: SampledPoint, to: SampledPoint): Double {
        if (from.source != to.source) return 0.0
        val separation = from.position.distanceTo(to.position)
        return from.source.spatialCorrelation * exp(-separation / CORRELATION_LENGTH_METRES)
    }

    /** Separation over which shared error is assumed to have decayed away. */
    private const val CORRELATION_LENGTH_METRES = 1.5

    /**
     * No measurement is claimed better than this, however the arithmetic comes out.
     * Nothing about a phone justifies a five-millimetre promise.
     */
    const val MINIMUM_SIGMA = 0.005
}
