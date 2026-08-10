package com.traza.core.geometry.capture

import kotlin.math.abs

/**
 * A detected plane, reduced to the few properties floor selection actually needs.
 *
 * Deliberately free of any ARCore type so the choice can be tested without a device —
 * getting the floor wrong ruins an entire room capture, and it is not something to
 * discover by walking around a real room with a phone.
 */
data class PlaneObservation(
    val id: Long,
    /** World height of the plane's centre. ARCore's Y axis is up. */
    val height: Double,
    /** Approximate extent, in square metres. */
    val area: Double,
    val isUpwardHorizontal: Boolean,
    val isDownwardHorizontal: Boolean = false,
    /** True when ARCore has already folded this plane into a larger one. */
    val isSubsumed: Boolean = false,
)

/** A merged horizontal surface: the floor, or the ceiling. */
data class HorizontalSurface(
    val height: Double,
    val area: Double,
    /** How many separate ARCore planes were merged to get here. */
    val planeCount: Int,
) {
    /** Enough floor to be worth trusting as *the* floor rather than a patch of one. */
    val isEstablished: Boolean get() = area >= FloorSelector.ESTABLISHED_AREA
}

/**
 * Picks the ceiling — docs/ACCURACY.md M11.
 *
 * The preferred way to get a room's height, because it needs no aiming at all: the
 * ceiling is a large, flat, well-observed plane and the height is simply its distance
 * above the floor. The fallback is a plumb measurement, which works everywhere but
 * depends on the user holding steady.
 *
 * Two traps that the floor does not have. A downward-facing plane can be the underside of
 * a table or a shelf, so anything below head height is rejected outright. And the *lowest*
 * qualifying surface wins rather than the largest — a dropped soffit over a kitchen
 * counter is genuinely the ceiling there, and in a room with both, the lower one is what
 * a person would measure to.
 */
object CeilingSelector {

    /** Below this it is furniture or a door frame, not a ceiling. */
    const val MINIMUM_HEIGHT_METRES = 1.8

    /** Above this it is a stairwell or an atrium, and a single height is meaningless. */
    const val MAXIMUM_HEIGHT_METRES = 6.0

    /** Smaller than this and it is a shelf rather than a surface overhead. */
    const val MINIMUM_AREA = 0.8

    fun select(planes: List<PlaneObservation>, floorHeight: Double): HorizontalSurface? {
        val usable = planes.filter { plane ->
            plane.isDownwardHorizontal &&
                !plane.isSubsumed &&
                plane.area >= MINIMUM_AREA &&
                (plane.height - floorHeight) in MINIMUM_HEIGHT_METRES..MAXIMUM_HEIGHT_METRES
        }
        if (usable.isEmpty()) return null

        val lowest = usable.minOf { it.height }
        val merged = usable.filter { it.height - lowest <= FloorSelector.MERGE_TOLERANCE_METRES }
        val totalArea = merged.sumOf { it.area }

        return HorizontalSurface(
            height = merged.sumOf { it.height * it.area } / totalArea,
            area = totalArea,
            planeCount = merged.size,
        )
    }
}

/**
 * Picks the dominant floor plane — docs/ACCURACY.md M2.
 *
 * ARCore habitually splits one physical floor into several plane instances as it
 * explores, and only sometimes tells you they are the same via `subsumedBy`. So planes at
 * effectively the same height are merged here regardless of what ARCore thinks, which is
 * what stops a room capture from being projected onto whichever fragment happened to be
 * under the reticle at the time.
 *
 * "Largest, tie-broken by lowest" is the rule in the accuracy document, with one
 * addition: **a substantially lower candidate wins even when it is somewhat smaller.**
 * A dining table is an upward-facing horizontal plane too, and in a cluttered room ARCore
 * often has more of the table than of the floor. Height is the stronger signal, so a
 * lower surface only has to be *competitive* on area, not larger.
 */
object FloorSelector {

    /** Planes within this of each other are treated as one physical surface. */
    const val MERGE_TOLERANCE_METRES = 0.06

    /** A lower cluster wins if it has at least this share of the largest cluster's area. */
    const val LOWER_SURFACE_AREA_SHARE = 0.45

    /** Below this the "floor" is a scrap and the capture should wait. */
    const val ESTABLISHED_AREA = 1.0

    fun select(planes: List<PlaneObservation>): HorizontalSurface? {
        val usable = planes.filter { it.isUpwardHorizontal && !it.isSubsumed && it.area > 0.0 }
        if (usable.isEmpty()) return null

        val clusters = cluster(usable)
        val largestArea = clusters.maxOf { it.area }

        // Among clusters big enough to be taken seriously, the lowest one is the floor.
        return clusters
            .filter { it.area >= largestArea * LOWER_SURFACE_AREA_SHARE }
            .minByOrNull { it.height }
    }

    /**
     * Single-pass clustering over height. The planes are sorted first, so a plane joins
     * the running cluster whenever it is within tolerance of the one before it.
     */
    private fun cluster(planes: List<PlaneObservation>): List<HorizontalSurface> {
        val sorted = planes.sortedBy { it.height }
        val clusters = mutableListOf<HorizontalSurface>()

        var members = mutableListOf(sorted.first())
        for (plane in sorted.drop(1)) {
            if (abs(plane.height - members.last().height) <= MERGE_TOLERANCE_METRES) {
                members += plane
            } else {
                clusters += merge(members)
                members = mutableListOf(plane)
            }
        }
        clusters += merge(members)
        return clusters
    }

    /**
     * Area-weighted height, so a large well-observed plane sets the level and a small
     * fragment sitting a couple of centimetres off does not drag it.
     */
    private fun merge(members: List<PlaneObservation>): HorizontalSurface {
        val totalArea = members.sumOf { it.area }
        val height = members.sumOf { it.height * it.area } / totalArea
        return HorizontalSurface(height = height, area = totalArea, planeCount = members.size)
    }
}
