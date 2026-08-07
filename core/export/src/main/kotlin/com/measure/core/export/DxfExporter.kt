package com.measure.core.export

import java.util.Locale

/**
 * A plan as DXF, the format every CAD package can open.
 *
 * This is the export a professional actually wants: a builder, an architect or a kitchen
 * fitter can drop it straight into AutoCAD, LibreCAD or QCAD and work from it. A PDF is
 * something to look at; a DXF is something to build on.
 *
 * Written in **metres, at full size**, with no scaling of any kind. CAD drawings are
 * always full size — scale is applied when printing, not when drawing — so a room five
 * metres across is five units across in the file. Anything else would silently make every
 * dimension taken off it wrong.
 *
 * The dialect is the minimal ASCII DXF that R12 defined and every reader since has kept
 * accepting. Newer versions offer more; none of it is needed to draw lines, and every
 * feature added is another way for one reader in ten to reject the file.
 */
object DxfExporter {

    private const val LAYER_WALLS = "WALLS"
    private const val LAYER_OPENINGS = "OPENINGS"
    private const val LAYER_TEXT = "ROOM_NAMES"
    private const val LAYER_NOTES = "NOTES"

    /** Text height in metres, so a room name reads sensibly at a domestic scale. */
    private const val TEXT_HEIGHT = 0.25

    /** How far below the drawing a note sits, in metres, so it is clear of the geometry. */
    private const val NOTE_DROP = 1.0

    fun export(plan: ExportablePlan): String = buildString {
        header(plan)
        tables()
        append("  0\nSECTION\n  2\nENTITIES\n")

        plan.rooms.forEach { room ->
            if (room.outline.size >= 3) {
                closedPolyline(room.outline, LAYER_WALLS)
            }
            room.openings.forEach { opening ->
                PlanGeometry.openingSpan(room, opening)?.let { (from, to) ->
                    line(from.x, from.y, to.x, to.y, LAYER_OPENINGS)
                }
            }
            if (room.outline.isNotEmpty()) {
                val centre = PlanGeometry.labelPoint(room.outline)
                text(centre.x, centre.y, room.name, LAYER_TEXT)
            }
        }

        // What the drawing is not entitled to claim, as a note on the drawing rather than
        // a comment. This is the export most likely to be measured off in earnest, so it
        // is the one where an unstated caveat does the most damage — and DXF has no
        // comment syntax a reader would show anyone.
        plan.arrangementCaveat?.let { caveat ->
            val points = plan.allPoints
            val left = points.minOfOrNull { it.x } ?: 0.0
            val bottom = points.minOfOrNull { it.y } ?: 0.0
            text(left, bottom - NOTE_DROP, caveat, LAYER_NOTES)
        }

        append("  0\nENDSEC\n")
        append("  0\nEOF\n")
    }

    private fun StringBuilder.header(plan: ExportablePlan) {
        val points = plan.allPoints
        val minX = points.minOfOrNull { it.x } ?: 0.0
        val minY = points.minOfOrNull { it.y } ?: 0.0
        val maxX = points.maxOfOrNull { it.x } ?: 0.0
        val maxY = points.maxOfOrNull { it.y } ?: 0.0

        append("  0\nSECTION\n  2\nHEADER\n")
        // $INSUNITS 6 is metres. Without it a reader has to guess, and most guess
        // millimetres — which makes a five metre room five millimetres across.
        append("  9\n\$INSUNITS\n 70\n     6\n")
        append("  9\n\$EXTMIN\n 10\n${number(minX)}\n 20\n${number(minY)}\n 30\n0.0\n")
        append("  9\n\$EXTMAX\n 10\n${number(maxX)}\n 20\n${number(maxY)}\n 30\n0.0\n")
        append("  0\nENDSEC\n")
    }

    private fun StringBuilder.tables() {
        append("  0\nSECTION\n  2\nTABLES\n")
        append("  0\nTABLE\n  2\nLAYER\n 70\n     4\n")
        listOf(
            // Colour 7 is "whatever the background is not", which is the only sane choice
            // for a drawing that may be opened on white or on black.
            LAYER_WALLS to 7,
            LAYER_OPENINGS to 5,
            LAYER_TEXT to 3,
            // On its own layer so it can be turned off deliberately rather than by
            // deleting it, and 1 is red — a note about what a drawing does not say should
            // not be quiet.
            LAYER_NOTES to 1,
        ).forEach { (name, colour) ->
            append("  0\nLAYER\n  2\n$name\n 70\n     0\n 62\n${colour.toString().padStart(6)}\n  6\nCONTINUOUS\n")
        }
        append("  0\nENDTAB\n")
        append("  0\nENDSEC\n")
    }

    private fun StringBuilder.closedPolyline(outline: List<com.measure.core.geometry.Vec2>, layer: String) {
        // POLYLINE/VERTEX/SEQEND rather than LWPOLYLINE: the old form is more verbose and
        // is the one every reader without exception understands.
        append("  0\nPOLYLINE\n  8\n$layer\n 66\n     1\n 70\n     1\n")
        append(" 10\n0.0\n 20\n0.0\n 30\n0.0\n")
        outline.forEach { point ->
            append("  0\nVERTEX\n  8\n$layer\n 10\n${number(point.x)}\n 20\n${number(point.y)}\n 30\n0.0\n")
        }
        append("  0\nSEQEND\n  8\n$layer\n")
    }

    private fun StringBuilder.line(x1: Double, y1: Double, x2: Double, y2: Double, layer: String) {
        append("  0\nLINE\n  8\n$layer\n")
        append(" 10\n${number(x1)}\n 20\n${number(y1)}\n 30\n0.0\n")
        append(" 11\n${number(x2)}\n 21\n${number(y2)}\n 31\n0.0\n")
    }

    private fun StringBuilder.text(x: Double, y: Double, value: String, layer: String) {
        append("  0\nTEXT\n  8\n$layer\n")
        append(" 10\n${number(x)}\n 20\n${number(y)}\n 30\n0.0\n")
        append(" 40\n${number(TEXT_HEIGHT)}\n")
        // Group 1 is the string itself. Newlines would end the group early and corrupt
        // everything after it, so they are flattened.
        append("  1\n${value.replace('\n', ' ')}\n")
        append(" 72\n     1\n 11\n${number(x)}\n 21\n${number(y)}\n 31\n0.0\n")
    }

    /** Full precision and a decimal point, in the C locale. See SvgExporter for why. */
    private fun number(value: Double) = String.format(Locale.ROOT, "%.4f", value)
}
