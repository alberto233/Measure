package com.measure.core.geometry.capture

/**
 * How much to trust the pose estimate right now. Ordered worst to best, so
 * `quality >= TrackingQuality.FAIR` is a meaningful comparison.
 */
enum class TrackingQuality { NONE, POOR, FAIR, GOOD }

/**
 * *Why* tracking is degraded, which matters more than the fact that it is.
 *
 * "Too dark" and "moving too fast" have opposite remedies, and an app that says only
 * "poor tracking" has told the user nothing they can act on (docs/ACCURACY.md M5).
 */
enum class TrackingIssue(val advice: String) {
    NONE("Ready"),
    INITIALISING("Move the phone slowly to start tracking"),
    NO_SURFACES_YET("Point at the floor and move slowly to find surfaces"),
    INSUFFICIENT_FEATURES("Not enough detail here — aim at a textured surface"),
    EXCESSIVE_MOTION("Slow down"),
    INSUFFICIENT_LIGHT("Too dark — turn a light on"),
    CAMERA_UNAVAILABLE("Camera unavailable"),
    UNKNOWN("Tracking lost — look around slowly"),
}

/**
 * The published tracking assessment. [canCapture] is the gate: we refuse to record a
 * point rather than record a bad one, because a competitor that records it anyway leaves
 * the user to discover the problem when the plan comes out wrong.
 */
data class TrackingStatus(
    val quality: TrackingQuality = TrackingQuality.NONE,
    val issue: TrackingIssue = TrackingIssue.INITIALISING,
    val trackedPlaneCount: Int = 0,
    val featurePointCount: Int = 0,
) {
    val canCapture: Boolean get() = quality >= TrackingQuality.FAIR
}

/**
 * Turns raw per-frame signals into a [TrackingStatus].
 *
 * Kept pure and out of the AR module so the thresholds are testable and reviewable in
 * one place rather than scattered through frame-handling code.
 */
object TrackingAssessor {

    // These count the feature points in a *single frame's* point cloud, not the whole
    // session map, which is why they are far smaller than a running total would suggest.
    // The first values tried were calibrated as if cumulative and reported "fair" on a
    // well-lit, textured scene that was in fact tracking perfectly.

    /** Below this, the frame is too sparse for a hit test to be worth anything. */
    const val SPARSE_FEATURE_THRESHOLD = 10

    /**
     * Above this the frame is rich enough that feature count stops being the limit.
     *
     * Lowered twice against the test device. A herringbone parquet floor at 1.6 m — about
     * as textured as a domestic scene gets — still came in under 45, so the per-frame
     * cloud is smaller than either guess assumed. This wants replacing with a figure
     * measured off the recorded-session corpus rather than another estimate.
     */
    const val HEALTHY_FEATURE_THRESHOLD = 25

    fun assess(
        isTracking: Boolean,
        reportedIssue: TrackingIssue,
        trackedPlaneCount: Int,
        featurePointCount: Int,
    ): TrackingStatus {
        // ARCore's own failure reason is authoritative and always wins: it knows things
        // about the frame — exposure, gyro rate — that we cannot see from here.
        if (!isTracking) {
            return TrackingStatus(
                quality = TrackingQuality.NONE,
                issue = if (reportedIssue == TrackingIssue.NONE) TrackingIssue.INITIALISING else reportedIssue,
                trackedPlaneCount = trackedPlaneCount,
                featurePointCount = featurePointCount,
            )
        }
        if (reportedIssue != TrackingIssue.NONE) {
            return TrackingStatus(
                quality = TrackingQuality.POOR,
                issue = reportedIssue,
                trackedPlaneCount = trackedPlaneCount,
                featurePointCount = featurePointCount,
            )
        }

        // Tracking is nominally fine. Now grade the map itself, because ARCore reports
        // TRACKING long before it has enough structure to hit-test usefully.
        val quality: TrackingQuality
        val issue: TrackingIssue
        when {
            featurePointCount < SPARSE_FEATURE_THRESHOLD -> {
                quality = TrackingQuality.POOR
                issue = TrackingIssue.INSUFFICIENT_FEATURES
            }

            trackedPlaneCount == 0 -> {
                quality = TrackingQuality.FAIR
                issue = TrackingIssue.NO_SURFACES_YET
            }

            featurePointCount < HEALTHY_FEATURE_THRESHOLD -> {
                quality = TrackingQuality.FAIR
                issue = TrackingIssue.NONE
            }

            else -> {
                quality = TrackingQuality.GOOD
                issue = TrackingIssue.NONE
            }
        }
        return TrackingStatus(quality, issue, trackedPlaneCount, featurePointCount)
    }
}
