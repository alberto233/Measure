package com.measure.ar

import com.google.ar.core.DepthPoint
import com.google.ar.core.HitResult
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.TrackingState
import com.measure.core.geometry.Vec3
import com.measure.core.geometry.capture.HitSource
import kotlin.math.abs

/** A hit test result, classified and reduced to the values the rest of the app uses. */
data class RankedHit(
    val position: Vec3,
    val range: Double,
    val source: HitSource,
)

/**
 * Picks the best of a frame's hit results — docs/ACCURACY.md M1.
 *
 * `Frame.hitTest` returns results sorted by distance along the ray, and taking the first
 * is what most sample code does. That is wrong for measuring: the nearest hit is often a
 * stray depth pixel or a lone feature point floating in front of the wall you are
 * actually aiming at. Ranking by *what kind of estimate produced it* prefers a fitted
 * plane — a model averaged over many observations — to a per-pixel guess, and that single
 * change is worth more accuracy than any amount of filtering afterwards.
 */
internal object HitRanking {

    /** How far off the established floor height a plane may sit and still be the floor. */
    private const val FLOOR_HEIGHT_TOLERANCE = 0.12

    /**
     * The best hit **on the floor plane**, ignoring everything else — even things nearer.
     *
     * Room capture needs this rather than [best]. Aiming at a floor corner in an occupied
     * room means aiming past whatever is piled in front of it, and ARCore will happily fit
     * a vertical plane to the front of a wardrobe and hand that back as the nearest hit.
     * Taking it and flattening its height onto the floor puts the corner under the
     * wardrobe's front face, a foot or more from where the user was pointing.
     *
     * Restricting to the floor makes the ray do the work: the point is where the line of
     * sight actually meets the floor, which is what "tap that corner" means. When nothing
     * on the floor is in the ray's path the caller gets null and says so, rather than
     * inventing a corner from the nearest available surface — a corner that is not on the
     * detected floor is almost always a mis-tap (docs/ACCURACY.md M1).
     */
    fun bestOnFloor(hits: List<HitResult>, floorHeight: Double): RankedHit? =
        hits.mapNotNull { hit -> classifyFloorHit(hit, floorHeight) }
            .minWithOrNull(compareBy({ it.source.ordinal }, { it.range }))

    private fun classifyFloorHit(hit: HitResult, floorHeight: Double): RankedHit? {
        if (hit.distance <= 0f) return null
        val plane = hit.trackable as? Plane ?: return null
        if (plane.trackingState != TrackingState.TRACKING) return null
        if (plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING) return null

        val pose = hit.hitPose
        // A table top is also an upward horizontal plane. Only the one at the room's
        // established floor height counts.
        if (abs(pose.ty() - floorHeight) > FLOOR_HEIGHT_TOLERANCE) return null

        return RankedHit(
            // Snapped to the floor exactly, so a corner carries none of the plane fit's
            // residual vertical wobble into the plan.
            position = Vec3(pose.tx().toDouble(), floorHeight, pose.tz().toDouble()),
            range = hit.distance.toDouble(),
            source = if (plane.isPoseInPolygon(pose)) HitSource.PLANE_POLYGON else HitSource.PLANE_INFINITE,
        )
    }

    fun best(hits: List<HitResult>): RankedHit? =
        hits.mapNotNull(::classify)
            // Source first (it is declared best-first), then nearest, because among
            // equally good estimates the nearer one is the more certain.
            .minWithOrNull(compareBy({ it.source.ordinal }, { it.range }))

    private fun classify(hit: HitResult): RankedHit? {
        // A hit behind the camera is a numerical artefact, not a target.
        if (hit.distance <= 0f) return null

        val source = when (val trackable = hit.trackable) {
            is Plane -> {
                if (trackable.trackingState != TrackingState.TRACKING) return null
                if (trackable.isPoseInPolygon(hit.hitPose)) {
                    HitSource.PLANE_POLYGON
                } else {
                    HitSource.PLANE_INFINITE
                }
            }

            is DepthPoint -> HitSource.DEPTH

            is Point -> {
                // A feature point with an estimated surface normal has been observed
                // enough times for ARCore to fit an orientation to it. One without is a
                // bare landmark and barely better than a guess.
                if (trackable.orientationMode != Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) {
                    return null
                }
                HitSource.FEATURE_POINT
            }

            is InstantPlacementPoint -> HitSource.INSTANT_PLACEMENT

            else -> return null
        }

        val pose = hit.hitPose
        return RankedHit(
            position = Vec3(pose.tx().toDouble(), pose.ty().toDouble(), pose.tz().toDouble()),
            range = hit.distance.toDouble(),
            source = source,
        )
    }
}
