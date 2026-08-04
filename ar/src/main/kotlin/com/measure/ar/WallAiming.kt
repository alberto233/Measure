package com.measure.ar

import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.measure.core.geometry.Vec3
import com.measure.core.geometry.capture.WallFace
import kotlin.math.abs

/** The wall under the reticle, together with the ARCore plane it came from. */
internal data class AimedWall(
    val face: WallFace,
    /** The ARCore plane behind it, when there was one. Null for a depth-fitted wall. */
    val plane: Plane?,
    val range: Double,
)

/**
 * Finds the wall the user is pointing at — docs/ACCURACY.md M10.
 *
 * Deliberately stricter than [HitRanking]. A point measurement can fall back through
 * depth hits and feature points, because a slightly worse estimate of a point is still an
 * estimate of that point. A wall cannot: the whole value of wall-face capture is that
 * ARCore has fitted a *plane* over many frames and many observations, which is a far
 * better line than anything a single ray can produce. Without a real fitted plane there
 * is nothing here worth having, and the honest answer is null.
 *
 * The aim must land **inside** the fitted polygon as well. The plane's infinite extension
 * covers most of the room, so accepting an extension hit would let a user "take" a wall
 * by pointing at the sofa in front of it.
 */
internal object WallAiming {

    fun aimedWall(hits: List<HitResult>): AimedWall? {
        hits.forEach { hit ->
            val wall = wallFrom(hit)
            if (wall != null) return wall
        }
        return null
    }

    private fun wallFrom(hit: HitResult): AimedWall? {
        if (hit.distance <= 0f) return null

        val plane = hit.trackable as? Plane ?: return null
        if (plane.trackingState != TrackingState.TRACKING) return null
        // A subsumed plane is one ARCore has merged into a larger one. Its parameters are
        // stale, and taking it would silently record a wall that no longer exists.
        if (plane.subsumedBy != null) return null
        if (plane.type != Plane.Type.VERTICAL) return null
        if (!plane.isPoseInPolygon(hit.hitPose)) return null

        val pose = plane.centerPose
        val normal = pose.yAxis

        return WallFace.fromVerticalPlane(
            id = plane.hashCode().toLong(),
            centre = Vec3(pose.tx().toDouble(), pose.ty().toDouble(), pose.tz().toDouble()),
            normal = Vec3(normal[0].toDouble(), normal[1].toDouble(), normal[2].toDouble()),
            extent = horizontalExtentOf(plane),
            range = hit.distance.toDouble(),
        )?.let { AimedWall(it, plane, hit.distance.toDouble()) }
    }

    /**
     * How much *wall* has been fitted, as opposed to how much surface.
     *
     * ARCore gives a vertical plane two in-plane extents without saying which is which,
     * and for a wall they mean very different things: one is the length along the floor,
     * which is what makes it a wall rather than a cupboard door, and the other is how far
     * up it the fit reaches, which does not. The in-plane axis with the smaller vertical
     * component is the horizontal one.
     */
    private fun horizontalExtentOf(plane: Plane): Double {
        val pose = plane.centerPose
        val alongX = abs(pose.xAxis[1]) <= abs(pose.zAxis[1])
        return if (alongX) plane.extentX.toDouble() else plane.extentZ.toDouble()
    }
}
