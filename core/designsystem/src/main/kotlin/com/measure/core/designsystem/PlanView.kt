package com.measure.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.measure.core.geometry.Vec2
import kotlin.math.max

/**
 * How a plan should be drawn. The same geometry serves a 130 dp thumbnail and a live
 * capture minimap, and only these details differ between them.
 */
data class PlanStyle(
    val strokeWidth: Float = 2.5f,
    val filled: Boolean = false,
    val showVertices: Boolean = true,
    val markStart: Boolean = false,
    val paddingDp: Float = 14f,
)

/**
 * Draws floor plans, fitted to whatever space it is given.
 *
 * Deliberately one implementation used everywhere a plan appears. `TECHNICAL_DESIGN.md`
 * makes the same point about PNG export rendering the editor's own drawing code: a plan
 * drawn twice by two pieces of code is a plan that will eventually be drawn two different
 * ways, and the version the user checks against a tape measure will be the wrong one.
 *
 * The scale is always uniform. Stretching a plan to fill its box would misrepresent the
 * room's proportions, which is the single thing a floor plan has to get right.
 */
@Composable
fun PlanView(
    outlines: List<List<Vec2>>,
    modifier: Modifier = Modifier,
    closed: Boolean = true,
    /** A moving point continuing the last outline, for a capture in progress. */
    preview: Vec2? = null,
    /** Show where the loop would close if the preview point were committed. */
    showClosingHint: Boolean = false,
    style: PlanStyle = PlanStyle(),
) {
    val points = outlines.flatten() + listOfNotNull(preview)
    if (points.isEmpty()) return

    Canvas(modifier) {
        val padding = style.paddingDp.dp.toPx()
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }

        // A single point, or a straight line of them, has zero extent on one axis.
        val spanX = max(maxX - minX, MINIMUM_SPAN_METRES)
        val spanY = max(maxY - minY, MINIMUM_SPAN_METRES)
        val scale = minOf((size.width - 2 * padding) / spanX, (size.height - 2 * padding) / spanY)
        val centreX = (minX + maxX) / 2.0
        val centreY = (minY + maxY) / 2.0

        fun project(point: Vec2) = Offset(
            x = size.width / 2f + ((point.x - centreX) * scale).toFloat(),
            // Plan +y is "away from where you started"; screen +y is down.
            y = size.height / 2f - ((point.y - centreY) * scale).toFloat(),
        )

        outlines.forEachIndexed { outlineIndex, outline ->
            if (outline.isEmpty()) return@forEachIndexed
            val isLast = outlineIndex == outlines.lastIndex
            val screen = outline.map(::project)

            // Fewer than three points cannot enclose anything, so it is a line rather than
            // a room — a standalone measurement, on a thumbnail that mixes the two.
            // Closing it would double it back on itself, and a vertical measurement
            // projects to a single point, which draws as nothing at all unless its ends
            // are marked.
            val isPolyline = outline.size < 3

            if (screen.size >= 2) {
                val path = Path().apply {
                    moveTo(screen.first().x, screen.first().y)
                    screen.drop(1).forEach { lineTo(it.x, it.y) }
                    if (closed && !isPolyline) close()
                }
                if (style.filled && closed && !isPolyline) {
                    drawPath(path, MeasureColours.Ready.copy(alpha = 0.22f))
                }
                drawPath(path, MeasureColours.Ink, style = Stroke(width = style.strokeWidth))
            }

            if (isPolyline) {
                screen.forEach {
                    drawCircle(MeasureColours.Ink, radius = style.strokeWidth * 1.6f, center = it)
                }
            }

            if (isLast && preview != null) {
                val previewScreen = project(preview)
                drawLine(
                    color = MeasureColours.Ready,
                    start = screen.last(),
                    end = previewScreen,
                    strokeWidth = style.strokeWidth,
                )
                if (showClosingHint && screen.size >= 2) {
                    drawLine(
                        color = MeasureColours.Sampling.copy(alpha = 0.5f),
                        start = previewScreen,
                        end = screen.first(),
                        strokeWidth = style.strokeWidth * 0.6f,
                    )
                }
            }

            if (style.showVertices) {
                screen.forEach { drawCircle(MeasureColours.Ink, radius = 3.5f, center = it) }
            }
            if (style.markStart && isLast) {
                screen.firstOrNull()?.let { drawCircle(MeasureColours.Blocked, radius = 5.5f, center = it) }
            }
        }
    }
}

/** Keeps the scale finite before there is any extent to fit. */
private const val MINIMUM_SPAN_METRES = 0.5
