package com.measure.core.geometry.plan

import com.measure.core.geometry.Vec2
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One run of a dimension string, between two consecutive ticks. */
data class DimensionSegment(val from: Double, val to: Double) {
    val length: Double get() = to - from
    val midpoint: Double get() = (from + to) / 2.0
}

/**
 * A dimension string: the chain of ticks and runs an architect draws beside a plan.
 *
 * The line runs along [direction] and sits where `normal · p == baseline`, which is the
 * near edge of what is being dimensioned. Whoever draws it pushes it outward along
 * `-normal` by however much clear space looks right, which is a screen decision and not a
 * geometric one.
 */
data class DimensionChain(
    val direction: Vec2,
    val normal: Vec2,
    /** Positions along [direction] where ticks fall, ascending. */
    val ticks: List<Double>,
    val segments: List<DimensionSegment>,
    val baseline: Double,
) {
    val overall: Double get() = if (ticks.size < 2) 0.0 else ticks.last() - ticks.first()

    /** Where a position along the chain sits in plan coordinates, on the baseline. */
    fun pointAt(along: Double): Vec2 = direction * along + normal * baseline
}

/**
 * The dimension strings for a plan — the numbers a floor plan carries without being asked.
 *
 * This is the answer to "how far across is it", which is what most people open a plan for,
 * and which the app previously made them derive by tapping two points accurately enough to
 * get a straight line. Reading a distance should not require a steady finger.
 *
 * Built along the plan's own dominant direction rather than the screen's axes, because
 * which way the room points depends only on which way the user happened to be facing when
 * they started capturing, and dimensions drawn at eleven degrees to the walls are worse
 * than none.
 *
 * Chains are broken at every corner, which is the standard notation and is also what makes
 * them answer more than one question: the overall width, and the width of each bay within
 * it, come from the same string.
 */
object DimensionChains {

    /** Ticks closer than this are one tick. Below it they are capture noise, not features. */
    const val MERGE_TOLERANCE_METRES = 0.05

    /**
     * The direction the plan is mostly built along.
     *
     * Walls at 0 and 90 degrees describe the same grid, so the angles are quadrupled
     * before averaging and quartered afterwards: that folds the four-fold symmetry away
     * and lets a length-weighted mean work directly. Averaging the raw angles would put
     * a square room's dominant direction at 45 degrees, exactly wrong.
     */
    fun dominantDirection(outlines: List<List<Vec2>>): Vec2 {
        var x = 0.0
        var y = 0.0

        outlines.forEach { outline ->
            if (outline.size < 2) return@forEach
            outline.indices.forEach { index ->
                val edge = outline[(index + 1) % outline.size] - outline[index]
                val length = edge.length
                if (length < Vec2.EPSILON) return@forEach
                val angle = atan2(edge.y, edge.x)
                x += length * cos(4 * angle)
                y += length * sin(4 * angle)
            }
        }

        if (hypot(x, y) < Vec2.EPSILON) return Vec2(1.0, 0.0)
        val angle = atan2(y, x) / 4.0
        return Vec2(cos(angle), sin(angle))
    }

    /**
     * The two chains for a plan: one along the dominant direction, one across it.
     *
     * Every room's corners feed both, so a plan of several rooms gets one overall
     * dimension rather than a thicket of competing ones — which is how a real drawing
     * does it, and the only version that stays readable on a phone.
     *
     * Empty when there is nothing with two distinct edges to dimension.
     */
    fun chains(outlines: List<List<Vec2>>): List<DimensionChain> {
        val points = outlines.filter { it.size >= 3 }.flatten()
        if (points.size < 3) return emptyList()

        val along = dominantDirection(outlines)
        val across = along.perpendicular()

        return listOfNotNull(
            chain(points, direction = along, normal = across),
            chain(points, direction = across, normal = along),
        )
    }

    private fun chain(points: List<Vec2>, direction: Vec2, normal: Vec2): DimensionChain? {
        val ticks = merge(points.map { it dot direction }.sorted())
        if (ticks.size < 2) return null

        return DimensionChain(
            direction = direction,
            normal = normal,
            ticks = ticks,
            segments = ticks.zipWithNext { from, to -> DimensionSegment(from, to) },
            // The near edge, so the chain is drawn outside the plan rather than over it.
            baseline = points.minOf { it dot normal },
        )
    }

    private fun merge(sorted: List<Double>): List<Double> {
        val merged = ArrayList<Double>(sorted.size)
        sorted.forEach { value ->
            val last = merged.lastOrNull()
            // Averaged into the tick it joins rather than discarded, so two corners a
            // centimetre apart give one tick between them and not one at whichever
            // happened to be first.
            if (last != null && value - last <= MERGE_TOLERANCE_METRES) {
                merged[merged.lastIndex] = (last + value) / 2.0
            } else {
                merged += value
            }
        }
        return merged
    }
}
