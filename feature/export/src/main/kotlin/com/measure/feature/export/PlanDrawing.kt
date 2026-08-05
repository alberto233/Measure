package com.measure.feature.export

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

    /** A margin in pixels, for the title block and so nothing touches the edge. */
    private const val MARGIN_FRACTION = 0.08f

    fun draw(canvas: Canvas, plan: ExportablePlan, width: Float, height: Float, unitLabel: String) {
        canvas.drawColor(BACKGROUND)

        val points = plan.allPoints
        val margin = minOf(width, height) * MARGIN_FRACTION

        if (points.isEmpty()) {
            canvas.drawText(
                "${plan.name} has no rooms yet",
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
        }

        // The title block. A drawing with no name on it is a drawing nobody can file.
        val footer = Paint().apply {
            color = MUTED
            textSize = bodyText * 0.8f
            isAntiAlias = true
        }
        canvas.drawText(plan.name, margin, height - margin * 0.9f, footer)
        canvas.drawText(
            "${plan.rooms.size} ${if (plan.rooms.size == 1) "room" else "rooms"} · " +
                "${number(plan.totalFloorArea)} m² · measured with Measure",
            margin,
            height - margin * 0.45f,
            footer,
        )
    }

    private fun areaOf(room: ExportableRoom, unitLabel: String) = "${number(room.floorArea)} $unitLabel"

    private fun number(value: Double) = String.format(Locale.ROOT, "%.2f", value)
}
