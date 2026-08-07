package com.measure.feature.capture

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.measure.core.designsystem.MeasureColours

/**
 * The aiming reticle: a ring at the exact centre of the screen, which is also the pixel
 * the hit test is run against.
 *
 * It is the app's main status display, and carries three things at once without any text.
 * Its **colour** says whether a capture would be accepted right now. Its **size** grows
 * when there is a surface under it, so the user feels the target acquire. And a **sweep**
 * around its rim tracks the multi-frame sample burst, which is what makes the half-second
 * of holding still feel like the app working rather than the app hanging.
 */
@Composable
internal fun Reticle(
    ready: Boolean,
    hasTarget: Boolean,
    samplingProgress: Float?,
    showAlignmentAxes: Boolean = false,
    /**
     * Whether the rectilinear assist is holding the point somewhere other than dead centre.
     *
     * Shown, and not merely applied. The corner is about to be placed away from the pixel
     * the user is aiming at, and an app that does that silently reads as broken aim; the
     * same behaviour with a visible lock reads as help. The marker in the scene is already
     * drawn at the snapped position, so the ring is what explains why it has moved.
     */
    snapped: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colour = when {
        samplingProgress != null -> MeasureColours.Sampling
        !ready -> MeasureColours.Blocked
        hasTarget -> MeasureColours.Ready
        else -> MeasureColours.Idle
    }

    val radius by animateFloatAsState(
        targetValue = if (hasTarget) 1f else 0.78f,
        label = "reticle radius",
    )

    Canvas(modifier) {
        val centre = Offset(size.width / 2f, size.height / 2f)

        // Full-width alignment axes. They cost almost nothing to draw and they turn the
        // whole screen into a straight edge: line one up with a skirting board or a door
        // frame and the phone is square to it, which is how you keep a consistent
        // trajectory towards a corner you cannot actually see.
        if (showAlignmentAxes) {
            val axis = Color.White.copy(alpha = 0.18f)
            val hairline = 1f.dp.toPx()
            drawLine(axis, Offset(0f, centre.y), Offset(size.width, centre.y), hairline)
            drawLine(axis, Offset(centre.x, 0f), Offset(centre.x, size.height), hairline)
        }

        val outer = OUTER_RADIUS_DP.dp.toPx() * radius
        val stroke = STROKE_DP.dp.toPx()

        // A soft dark halo so the reticle stays legible against a white wall.
        drawCircle(Color.Black.copy(alpha = 0.35f), outer, centre, style = Stroke(stroke * 2.4f))
        drawCircle(colour, outer, centre, style = Stroke(stroke))

        // Four ticks pointing inward at the aim point. Cheaper on attention than a
        // full crosshair, which would obscure the very detail being aimed at.
        val tickInner = outer * 0.34f
        val tickOuter = outer * 0.58f
        listOf(
            Offset(0f, -1f), Offset(0f, 1f), Offset(-1f, 0f), Offset(1f, 0f),
        ).forEach { direction ->
            drawLine(
                color = colour,
                start = centre + direction * tickInner,
                end = centre + direction * tickOuter,
                strokeWidth = stroke,
            )
        }

        // A second ring outside the first, in the accent used nowhere else on this screen.
        // Distinct from the ready/blocked colours, which carry a different meaning and must
        // not be overloaded: those say whether a capture would be accepted, this says where
        // it would land.
        if (snapped) {
            val lockRadius = outer + stroke * 4f
            drawCircle(Color.Black.copy(alpha = 0.35f), lockRadius, centre, style = Stroke(stroke * 1.8f))
            drawCircle(MeasureColours.Accent, lockRadius, centre, style = Stroke(stroke))
        }

        if (samplingProgress != null) {
            val sweepRadius = outer + stroke * 2.5f
            drawArc(
                color = MeasureColours.Sampling,
                startAngle = -90f,
                sweepAngle = 360f * samplingProgress,
                useCenter = false,
                topLeft = Offset(centre.x - sweepRadius, centre.y - sweepRadius),
                size = Size(sweepRadius * 2, sweepRadius * 2),
                style = Stroke(stroke * 1.6f),
            )
        }
    }
}

private const val OUTER_RADIUS_DP = 26f
private const val STROKE_DP = 2f
