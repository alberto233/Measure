package com.measure.feature.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.measure.core.geometry.Vec2
import kotlin.math.max

/**
 * A live plan view of the room being captured.
 *
 * The AR view alone is a poor way to understand a room: the user is inside it, looking at
 * one wall at a time, and cannot see the shape they are making. This is the feedback that
 * turns a sequence of taps into something recognisable as a floor plan while there is
 * still time to fix it — a corner tapped in the wrong place is obvious here and invisible
 * through the camera.
 *
 * Auto-scaled to whatever has been captured so far, so it is useful from the second
 * corner rather than only at the end.
 */
@Composable
internal fun RoomMinimap(
    corners: List<Vec2>,
    preview: Vec2?,
    closed: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
) {
    if (corners.isEmpty()) return

    Canvas(
        modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(CaptureColours.Scrim),
    ) {
        val points = corners + listOfNotNull(preview)
        val transform = fitToCanvas(points, this.size.width, this.size.height)

        val screen = corners.map(transform)
        val previewScreen = preview?.let(transform)

        // Walls captured so far.
        if (screen.size >= 2) {
            val path = Path().apply {
                moveTo(screen.first().x, screen.first().y)
                screen.drop(1).forEach { lineTo(it.x, it.y) }
                if (closed) close()
            }
            if (closed) {
                drawPath(path, CaptureColours.Ready.copy(alpha = 0.22f))
            }
            drawPath(path, CaptureColours.OnScrim, style = Stroke(width = 2.5f))
        }

        // The wall being walked, and where the loop would close.
        if (previewScreen != null && screen.isNotEmpty()) {
            drawLine(CaptureColours.Ready, screen.last(), previewScreen, strokeWidth = 2.5f)
            if (!closed && screen.size >= 2) {
                drawLine(
                    color = CaptureColours.Sampling.copy(alpha = 0.5f),
                    start = previewScreen,
                    end = screen.first(),
                    strokeWidth = 1.5f,
                )
            }
        }

        screen.forEach { drawCircle(CaptureColours.OnScrim, radius = 3.5f, center = it) }
        // The corner to come back to.
        screen.firstOrNull()?.let {
            if (!closed) drawCircle(CaptureColours.Blocked, radius = 5.5f, center = it)
        }
    }
}

/**
 * Builds a plan-to-canvas transform that fits [points] with a uniform scale.
 *
 * Uniform matters: a plan stretched to fill the box would misrepresent the room's
 * proportions, which is the one thing a floor plan has to get right.
 */
private fun DrawScope.fitToCanvas(
    points: List<Vec2>,
    width: Float,
    height: Float,
): (Vec2) -> Offset {
    val padding = 14.dp.toPx()
    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }

    // A single point, or a perfectly straight line of them, has zero extent on an axis.
    val spanX = max(maxX - minX, MINIMUM_SPAN_METRES)
    val spanY = max(maxY - minY, MINIMUM_SPAN_METRES)
    val scale = minOf((width - 2 * padding) / spanX, (height - 2 * padding) / spanY)

    val centreX = (minX + maxX) / 2.0
    val centreY = (minY + maxY) / 2.0

    return { point ->
        Offset(
            x = width / 2f + ((point.x - centreX) * scale).toFloat(),
            // Plan +y is "away"; screen +y is down, so the axis flips.
            y = height / 2f - ((point.y - centreY) * scale).toFloat(),
        )
    }
}

/** Keeps the scale finite before there is any extent to fit. */
private const val MINIMUM_SPAN_METRES = 0.5
