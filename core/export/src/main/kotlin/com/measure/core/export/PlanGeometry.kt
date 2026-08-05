package com.measure.core.export

import com.measure.core.geometry.Vec2

/**
 * Geometry every renderer of a plan needs, wherever it draws.
 *
 * Lifted out of [SvgExporter], where it started as a private helper and then had to be
 * reached by the PNG and PDF drawing as well. Two renderers computing "where does this
 * door sit on this wall" separately is precisely the duplication `TECHNICAL_DESIGN.md`
 * warns about: they agree until one of them is changed.
 */
object PlanGeometry {

    /** Where an opening starts and ends along its wall, in plan coordinates. */
    fun openingSpan(room: ExportableRoom, opening: ExportableOpening): Pair<Vec2, Vec2>? {
        val outline = room.outline
        if (outline.size < 3 || opening.wallIndex !in outline.indices) return null
        val from = outline[opening.wallIndex]
        val to = outline[(opening.wallIndex + 1) % outline.size]
        val length = from.distanceTo(to)
        if (length < Vec2.EPSILON) return null

        val start = (opening.offset / length).coerceIn(0.0, 1.0)
        val end = ((opening.offset + opening.width) / length).coerceIn(0.0, 1.0)
        val span = to - from
        return (from + span * start) to (from + span * end)
    }

    /**
     * Where a room's label goes.
     *
     * The average of the corners rather than the true centroid of the area. For an
     * L-shaped room the true centroid can fall outside the room entirely, which would put
     * the name in the hallway.
     */
    fun labelPoint(outline: List<Vec2>): Vec2 {
        if (outline.isEmpty()) return Vec2.ZERO
        return outline.fold(Vec2.ZERO) { total, point -> total + point } / outline.size.toDouble()
    }
}
