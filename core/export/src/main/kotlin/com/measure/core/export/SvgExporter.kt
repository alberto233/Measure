package com.measure.core.export

import com.measure.core.geometry.Vec2
import java.util.Locale

/**
 * A plan as an SVG drawing.
 *
 * SVG rather than a bitmap wherever possible: it prints at any size, it can be pulled into
 * a document and rescaled without turning to mush, and — because it is text — it is the
 * one image format whose correctness can be checked by a unit test rather than by looking
 * at it.
 *
 * Drawn at a **true scale**, with the scale stated on the drawing. A floor plan whose
 * relationship to reality is unstated is a picture; one that says 1:50 is a drawing
 * somebody can measure off. Getting that wrong is worse than not exporting at all, so the
 * factor is written into the file rather than left implied by the page size.
 */
object SvgExporter {

    /** Millimetres on the page per metre in the room, at 1:50. */
    private const val MILLIMETRES_PER_METRE = 1000.0

    /** A margin wide enough for the dimension text that sits outside the plan. */
    private const val MARGIN_MM = 24.0

    /** Line weights in millimetres, which is how a drawing specifies them. */
    private const val WALL_WEIGHT_MM = 0.6
    private const val OPENING_WEIGHT_MM = 0.25
    private const val TEXT_HEIGHT_MM = 3.0

    /**
     * @param scaleDenominator the drawing scale, as in 1:[scaleDenominator]. Fifty is the
     *   usual choice for a domestic floor plan and puts a large house on one A3 sheet.
     */
    fun export(plan: ExportablePlan, scaleDenominator: Double = 50.0): String {
        val points = plan.allPoints
        if (points.isEmpty()) return emptyDocument(plan)

        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }

        val scale = MILLIMETRES_PER_METRE / scaleDenominator
        val width = (maxX - minX) * scale + 2 * MARGIN_MM
        val height = (maxY - minY) * scale + 2 * MARGIN_MM

        // SVG's y grows downwards and a plan's grows away from the viewer, so the whole
        // drawing is flipped once here rather than at every point.
        fun x(point: Vec2) = MARGIN_MM + (point.x - minX) * scale
        fun y(point: Vec2) = MARGIN_MM + (maxY - point.y) * scale

        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\" " +
                    "width=\"${mm(width)}mm\" height=\"${mm(height)}mm\" " +
                    "viewBox=\"0 0 ${mm(width)} ${mm(height)}\">\n",
            )
            append("  <title>${escape(plan.name)}</title>\n")
            append("  <rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n")

            plan.rooms.forEach { room ->
                if (room.outline.size < 3) return@forEach
                append("  <g id=\"${escape(room.name)}\">\n")

                val path = room.outline.joinToString(" ") { "${mm(x(it))},${mm(y(it))}" }
                append(
                    "    <polygon points=\"$path\" fill=\"#f2f2f2\" stroke=\"#000000\" " +
                        "stroke-width=\"${mm(WALL_WEIGHT_MM)}\" stroke-linejoin=\"miter\"/>\n",
                )

                // Openings as a gap drawn back over the wall in white, which is how a
                // plan shows them and survives being printed in black and white.
                room.openings.forEach { opening ->
                    openingSpan(room, opening)?.let { (from, to) ->
                        append(
                            "    <line x1=\"${mm(x(from))}\" y1=\"${mm(y(from))}\" " +
                                "x2=\"${mm(x(to))}\" y2=\"${mm(y(to))}\" " +
                                "stroke=\"#ffffff\" stroke-width=\"${mm(WALL_WEIGHT_MM * 2)}\"/>\n",
                        )
                        append(
                            "    <line x1=\"${mm(x(from))}\" y1=\"${mm(y(from))}\" " +
                                "x2=\"${mm(x(to))}\" y2=\"${mm(y(to))}\" " +
                                "stroke=\"#000000\" stroke-width=\"${mm(OPENING_WEIGHT_MM)}\" " +
                                "stroke-dasharray=\"1,1\"/>\n",
                        )
                    }
                }

                val centroid = centroidOf(room.outline)
                append(
                    "    <text x=\"${mm(x(centroid))}\" y=\"${mm(y(centroid))}\" " +
                        "font-family=\"sans-serif\" font-size=\"${mm(TEXT_HEIGHT_MM)}\" " +
                        "text-anchor=\"middle\">${escape(room.name)}</text>\n",
                )
                append(
                    "    <text x=\"${mm(x(centroid))}\" y=\"${mm(y(centroid) + TEXT_HEIGHT_MM * 1.4)}\" " +
                        "font-family=\"sans-serif\" font-size=\"${mm(TEXT_HEIGHT_MM * 0.8)}\" " +
                        "text-anchor=\"middle\">${number(room.floorArea)} m²</text>\n",
                )
                append("  </g>\n")
            }

            // The scale, stated. Without it this is a picture rather than a drawing.
            append(
                "  <text x=\"${mm(MARGIN_MM)}\" y=\"${mm(height - MARGIN_MM / 3)}\" " +
                    "font-family=\"sans-serif\" font-size=\"${mm(TEXT_HEIGHT_MM * 0.8)}\">" +
                    "${escape(plan.name)} · 1:${number(scaleDenominator)} at A-size · " +
                    "measured with Measure</text>\n",
            )
            append("</svg>\n")
        }
    }

    private fun emptyDocument(plan: ExportablePlan) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\" " +
                "width=\"100mm\" height=\"60mm\" viewBox=\"0 0 100 60\">\n",
        )
        append("  <title>${escape(plan.name)}</title>\n")
        append("  <rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n")
        append(
            "  <text x=\"50\" y=\"30\" font-family=\"sans-serif\" font-size=\"4\" " +
                "text-anchor=\"middle\">${escape(plan.name)} has no rooms yet</text>\n",
        )
        append("</svg>\n")
    }

    internal fun openingSpan(room: ExportableRoom, opening: ExportableOpening): Pair<Vec2, Vec2>? {
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

    internal fun centroidOf(outline: List<Vec2>): Vec2 {
        if (outline.isEmpty()) return Vec2.ZERO
        val sum = outline.fold(Vec2.ZERO) { total, point -> total + point }
        return sum / outline.size.toDouble()
    }

    /**
     * Millimetres, to two places.
     *
     * Locale.ROOT is load-bearing rather than defensive: on a phone set to Spanish the
     * default locale writes a decimal comma, and an SVG coordinate with a comma in it is
     * a different pair of numbers. That would produce a file that renders as nonsense on
     * exactly the devices this app is being tested on.
     */
    private fun mm(value: Double) = String.format(Locale.ROOT, "%.2f", value)

    private fun number(value: Double) = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

    private fun escape(text: String) = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
