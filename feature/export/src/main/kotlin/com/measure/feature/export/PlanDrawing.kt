package com.measure.feature.export

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.measure.core.export.ExportablePlan
import com.measure.core.export.ExportableRoom
import com.measure.core.export.PlanGeometry
import com.measure.core.geometry.Vec2
import java.util.Locale

/**
 * Draws a plan onto an Android canvas, for the export formats that are pictures.
 *
 * One implementation serving both PNG and PDF, deliberately. `TECHNICAL_DESIGN.md` makes
 * the point that a plan drawn twice by two pieces of code is a plan that will eventually
 * be drawn two different ways, and the version the user checks against a tape will be the
 * wrong one. PNG and PDF differ only in what they are handed to draw on.
 *
 * Light on purpose, unlike the app. These end up printed, attached to a quote, or opened
 * on a laptop — a dark drawing would come out of a printer as a page of toner and be
 * unreadable in every context an export exists for.
 */
internal object PlanDrawing {

    private const val BACKGROUND = Color.WHITE
    private val WALL = Color.rgb(20, 20, 20)
    private val FILL = Color.rgb(242, 242, 242)
    private val MUTED = Color.rgb(110, 110, 110)

    /** A margin, for the dimension strings, the title block and a little clear air. */
    private const val MARGIN_FRACTION = 0.13f

    /** How far the dimension lines stand off the drawing, as a share of the margin. */
    private const val RUN_OFFSET = 0.32f
    private const val OVERALL_OFFSET = 0.66f

    fun draw(
        resources: Resources,
        canvas: Canvas,
        plan: ExportablePlan,
        width: Float,
        height: Float,
        unitLabel: String,
    ) {
        canvas.drawColor(BACKGROUND)

        val points = plan.allPoints
        val margin = minOf(width, height) * MARGIN_FRACTION

        if (points.isEmpty()) {
            canvas.drawText(
                resources.getString(R.string.export_drawing_empty, plan.name),
                width / 2f,
                height / 2f,
                Paint().apply {
                    color = MUTED
                    textSize = minOf(width, height) * 0.04f
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                },
            )
            return
        }

        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }

        // Uniform, always. Stretching a plan to fill the page would misrepresent the
        // room's proportions, which is the single thing a floor plan has to get right.
        val usableWidth = (width - 2 * margin).coerceAtLeast(1f)
        val usableHeight = (height - 3 * margin).coerceAtLeast(1f)
        val scale = minOf(
            usableWidth / (maxX - minX).coerceAtLeast(0.5).toFloat(),
            usableHeight / (maxY - minY).coerceAtLeast(0.5).toFloat(),
        )

        val drawnWidth = (maxX - minX).toFloat() * scale
        val drawnHeight = (maxY - minY).toFloat() * scale
        val originX = margin + (usableWidth - drawnWidth) / 2f
        val originY = margin + (usableHeight - drawnHeight) / 2f

        // A canvas grows downwards and a plan grows away from the viewer, so the drawing
        // is flipped here once rather than at every point.
        fun x(point: Vec2) = originX + (point.x - minX).toFloat() * scale
        fun y(point: Vec2) = originY + (maxY - point.y).toFloat() * scale

        val lineWeight = (minOf(width, height) * 0.0035f).coerceAtLeast(1.2f)
        val bodyText = minOf(width, height) * 0.022f

        val fill = Paint().apply {
            color = FILL
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val outline = Paint().apply {
            color = WALL
            style = Paint.Style.STROKE
            strokeWidth = lineWeight
            strokeJoin = Paint.Join.MITER
            isAntiAlias = true
        }
        val gap = Paint().apply {
            color = BACKGROUND
            style = Paint.Style.STROKE
            strokeWidth = lineWeight * 2.4f
            isAntiAlias = true
        }
        val opening = Paint().apply {
            color = MUTED
            style = Paint.Style.STROKE
            strokeWidth = lineWeight * 0.6f
            isAntiAlias = true
        }
        val label = Paint().apply {
            color = WALL
            textSize = bodyText
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val sublabel = Paint().apply {
            color = MUTED
            textSize = bodyText * 0.8f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        plan.rooms.forEach { room ->
            if (room.outline.size < 3) return@forEach

            val path = Path()
            room.outline.forEachIndexed { index, corner ->
                if (index == 0) path.moveTo(x(corner), y(corner)) else path.lineTo(x(corner), y(corner))
            }
            path.close()
            canvas.drawPath(path, fill)
            canvas.drawPath(path, outline)

            room.openings.forEach { hole ->
                PlanGeometry.openingSpan(room, hole)?.let { (from, to) ->
                    canvas.drawLine(x(from), y(from), x(to), y(to), gap)
                    canvas.drawLine(x(from), y(from), x(to), y(to), opening)
                }
            }

            val centre = PlanGeometry.labelPoint(room.outline)
            canvas.drawText(room.name, x(centre), y(centre), label)
            canvas.drawText(areaOf(room, unitLabel), x(centre), y(centre) + bodyText * 1.1f, sublabel)
            room.ceilingHeight?.let {
                canvas.drawText(
                    resources.getString(R.string.export_drawing_height, number(it)),
                    x(centre),
                    y(centre) + bodyText * 2.0f,
                    sublabel,
                )
            }
        }

        // Dimension strings. Every run, unlike in the app where one is shown at a time:
        // on paper there is nobody to tap for the number, so a drawing that does not
        // carry its dimensions is a picture of a room rather than a description of one.
        val dimensionLine = Paint().apply {
            color = WALL
            style = Paint.Style.STROKE
            strokeWidth = lineWeight * 0.5f
            isAntiAlias = true
        }
        val witness = Paint().apply {
            color = MUTED
            style = Paint.Style.STROKE
            strokeWidth = lineWeight * 0.3f
            isAntiAlias = true
        }
        val dimensionText = Paint().apply {
            color = WALL
            textSize = bodyText * 0.72f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        plan.dimensions.forEach { chain ->
            if (chain.ticks.size < 2) return@forEach

            fun at(along: Double, offset: Float): Pair<Float, Float> {
                val base = chain.pointAt(along)
                // The normal points into the plan, so the offset goes the other way; the
                // canvas flip means only the y component changes sign on the way out.
                val px = x(base) - chain.normal.x.toFloat() * offset
                val py = y(base) + chain.normal.y.toFloat() * offset
                return px to py
            }

            chain.ticks.forEach { tick ->
                val (x1, y1) = at(tick, margin * 0.06f)
                val (x2, y2) = at(tick, margin * (OVERALL_OFFSET + 0.1f))
                canvas.drawLine(x1, y1, x2, y2, witness)
            }

            val (runFromX, runFromY) = at(chain.ticks.first(), margin * RUN_OFFSET)
            val (runToX, runToY) = at(chain.ticks.last(), margin * RUN_OFFSET)
            canvas.drawLine(runFromX, runFromY, runToX, runToY, dimensionLine)

            chain.segments.forEach { segment ->
                val (lx, ly) = at(segment.midpoint, margin * RUN_OFFSET - bodyText * 0.4f)
                canvas.drawText("${number(segment.length)} m", lx, ly, dimensionText)
            }

            if (chain.segments.size > 1) {
                val (fromX, fromY) = at(chain.ticks.first(), margin * OVERALL_OFFSET)
                val (toX, toY) = at(chain.ticks.last(), margin * OVERALL_OFFSET)
                canvas.drawLine(fromX, fromY, toX, toY, dimensionLine)

                val middle = (chain.ticks.first() + chain.ticks.last()) / 2.0
                val (ox, oy) = at(middle, margin * OVERALL_OFFSET - bodyText * 0.4f)
                canvas.drawText(
                    "${number(chain.overall)} m",
                    ox,
                    oy,
                    Paint(dimensionText).apply { isFakeBoldText = true },
                )
            }
        }

        // Distances drawn on the plan, dashed and marked, so they are never mistaken for
        // a wall or for something measured in the room.
        if (plan.distances.isNotEmpty()) {
            val derived = Paint().apply {
                color = Color.rgb(176, 96, 0)
                style = Paint.Style.STROKE
                strokeWidth = lineWeight * 0.8f
                pathEffect = android.graphics.DashPathEffect(
                    floatArrayOf(lineWeight * 4f, lineWeight * 3f),
                    0f,
                )
                isAntiAlias = true
            }
            val derivedText = Paint().apply {
                color = Color.rgb(176, 96, 0)
                textSize = bodyText * 0.72f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            plan.distances.forEach { distance ->
                canvas.drawLine(
                    x(distance.from),
                    y(distance.from),
                    x(distance.to),
                    y(distance.to),
                    derived,
                )
                canvas.drawText(
                    "~ ${number(distance.length)} m",
                    (x(distance.from) + x(distance.to)) / 2f,
                    (y(distance.from) + y(distance.to)) / 2f - bodyText * 0.4f,
                    derivedText,
                )
            }
        }

        // The title block. A drawing with no name on it is a drawing nobody can file.
        val footer = Paint().apply {
            color = MUTED
            textSize = bodyText * 0.8f
            isAntiAlias = true
        }
        canvas.drawText(plan.title, margin, height - margin * 0.9f, footer)
        canvas.drawText(
            resources.getString(
                R.string.export_drawing_footer,
                resources.getQuantityString(
                    R.plurals.export_drawing_rooms,
                    plan.rooms.size,
                    plan.rooms.size,
                ),
                number(plan.totalFloorArea),
            ),
            margin,
            height - margin * 0.45f,
            footer,
        )
        // And what the drawing is not entitled to claim, when that applies. Coloured like
        // the derived distances, because it says the same kind of thing: this part came
        // off the drawing rather than out of the room.
        plan.arrangementCaveat?.let { caveat ->
            canvas.drawText(
                caveat,
                margin,
                height - margin * 1.35f,
                Paint(footer).apply { color = Color.rgb(176, 96, 0) },
            )
        }
    }

    private fun areaOf(room: ExportableRoom, unitLabel: String) = "${number(room.floorArea)} $unitLabel"

    private fun number(value: Double) = String.format(Locale.ROOT, "%.2f", value)
}
