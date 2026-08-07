package com.measure.feature.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.measure.core.designsystem.MeasureColours

/**
 * Line drawings for the guidance steps.
 *
 * **Drawn rather than imported.** These are vector art in the same sense an SVG is —
 * resolution independent, a few paths each — but expressed as Compose paths so they take
 * their colours from `MeasureColours` rather than baking them in. The palette is the one
 * thing about this app's appearance that has already moved once, and art that cannot
 * follow it is art that goes stale in the one place a new user looks first.
 *
 * The vocabulary is deliberately the plan renderer's, because that is what the user is
 * about to be looking at: ink hairlines for structure, the accent for the one thing each
 * picture is about, a wash for anything approximate. Nothing here is decorative — each
 * drawing shows the *mistake* being described as much as the correct behaviour, because
 * "stand 1–3 m back" is advice and "not from the doorway" is the thing people actually do.
 *
 * All geometry is in a 0..1 box and scaled at draw time, so a step can be rendered at any
 * height without a second set of numbers.
 */
internal enum class StepArt { TOLERANCE, WALK, LIGHT, SLOW, JOINT }

@Composable
internal fun StepIllustration(art: StepArt, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val hairline = 1.5.dp.toPx()
        val line = 2.dp.toPx()

        when (art) {
            StepArt.TOLERANCE -> drawTolerance(hairline, line)
            StepArt.WALK -> drawWalk(hairline, line)
            StepArt.LIGHT -> drawLight(hairline, line)
            StepArt.SLOW -> drawSlow(hairline, line)
            StepArt.JOINT -> drawJoint(hairline, line)
        }
    }
}

private fun DrawScope.at(x: Float, y: Float) = Offset(x * size.width, y * size.height)

/**
 * A measured length whose far end is a band rather than a point.
 *
 * The whole accuracy message in one picture: the number is real, and it has a width. The
 * band is drawn to scale-ish — noticeably short of the run — so it reads as a tolerance
 * rather than as uncertainty about whether the wall is there at all.
 */
private fun DrawScope.drawTolerance(hairline: Float, line: Float) {
    val y = 0.56f

    // The band the far end actually lives in. Painted first: it is ground, and drawing it
    // last buried the arrowhead that lands inside it.
    val bandLeft = 0.79f
    val bandRight = 0.95f
    drawRect(
        color = MeasureColours.AccentWash,
        topLeft = at(bandLeft, 0.38f),
        size = Size((bandRight - bandLeft) * size.width, 0.36f * size.height),
    )
    for (x in listOf(bandLeft, bandRight)) {
        drawLine(MeasureColours.Accent, at(x, 0.38f), at(x, 0.74f), hairline)
    }

    // Extension lines, exactly as the plan renderer draws them.
    for (x in listOf(0.13f, 0.87f)) {
        drawLine(MeasureColours.Line, at(x, 0.30f), at(x, 0.78f), hairline)
    }

    drawLine(MeasureColours.Ink, at(0.13f, y), at(0.87f, y), line, cap = StrokeCap.Round)
    for ((x, direction) in listOf(0.13f to 1f, 0.87f to -1f)) {
        for (dy in listOf(-0.05f, 0.05f)) {
            drawLine(MeasureColours.Ink, at(x, y), at(x + direction * 0.05f, y + dy), hairline)
        }
    }
}

/**
 * A room in plan, with the walk that measures it and the shortcut that does not.
 *
 * The dashed loop is the advice. The single long ray from the doorway to the far corner is
 * the mistake, drawn faint and crossed, because it is what an uninstructed person does and
 * the reason their first scan disappoints them (docs/ACCURACY.md M4).
 */
private fun DrawScope.drawWalk(hairline: Float, line: Float) {
    val left = 0.16f
    val right = 0.84f
    val top = 0.20f
    val bottom = 0.80f

    drawRect(
        color = MeasureColours.Ink,
        topLeft = at(left, top),
        size = Size((right - left) * size.width, (bottom - top) * size.height),
        style = Stroke(line),
    )

    // The bad idea: stand still, point across the room.
    val doorway = at(left + 0.06f, bottom - 0.04f)
    val farCorner = at(right, top)
    drawLine(
        color = MeasureColours.InkFaint,
        start = doorway,
        end = farCorner,
        strokeWidth = hairline,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
    )
    val cross = 0.035f
    val mid = Offset((doorway.x + farCorner.x) / 2f, (doorway.y + farCorner.y) / 2f)
    for (sign in listOf(-1f, 1f)) {
        drawLine(
            MeasureColours.InkFaint,
            Offset(mid.x - cross * size.width, mid.y - sign * cross * size.height),
            Offset(mid.x + cross * size.width, mid.y + sign * cross * size.height),
            hairline,
        )
    }

    // The walk itself, inside the walls, with the corners it stops at.
    val inset = 0.09f
    val path = Path().apply {
        moveTo(at(left + inset, bottom - inset).x, at(left + inset, bottom - inset).y)
        lineTo(at(left + inset, top + inset).x, at(left + inset, top + inset).y)
        lineTo(at(right - inset, top + inset).x, at(right - inset, top + inset).y)
        lineTo(at(right - inset, bottom - inset).x, at(right - inset, bottom - inset).y)
    }
    drawPath(
        path = path,
        color = MeasureColours.Accent,
        style = Stroke(line, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
    )

    for (corner in listOf(left to top, right to top, right to bottom, left to bottom)) {
        drawCircle(MeasureColours.Accent, 0.016f * size.width, at(corner.first, corner.second))
    }
}

/** A wall the camera can read, and one it cannot: hatching against blank. */
private fun DrawScope.drawLight(hairline: Float, line: Float) {
    val floor = 0.76f
    drawLine(MeasureColours.Ink, at(0.08f, floor), at(0.92f, floor), line, cap = StrokeCap.Round)

    // Left: texture, which is what tracking actually holds on to.
    drawRect(
        color = MeasureColours.Ink,
        topLeft = at(0.10f, 0.26f),
        size = Size(0.34f * size.width, (floor - 0.26f) * size.height),
        style = Stroke(hairline),
    )
    val wallTop = at(0.10f, 0.26f)
    val wallBottom = at(0.44f, floor)
    val wallHeight = wallBottom.y - wallTop.y
    clipRect(wallTop.x, wallTop.y, wallBottom.x, wallBottom.y) {
        var x = wallTop.x - wallHeight
        val step = 0.055f * size.width
        while (x < wallBottom.x) {
            drawLine(
                color = MeasureColours.Line,
                start = Offset(x, wallBottom.y),
                end = Offset(x + wallHeight, wallTop.y),
                strokeWidth = hairline,
            )
            x += step
        }
    }

    // Right: blank, and lit — the lamp is the fix for both halves.
    drawRect(
        color = MeasureColours.Ink,
        topLeft = at(0.56f, 0.26f),
        size = Size(0.34f * size.width, (floor - 0.26f) * size.height),
        style = Stroke(hairline),
    )
    val lamp = at(0.73f, 0.45f)
    drawCircle(MeasureColours.AccentWash, 0.075f * size.width, lamp)
    drawCircle(MeasureColours.Accent, 0.075f * size.width, lamp, style = Stroke(line))
    for (angle in 0 until 8) {
        val radians = angle * (Math.PI / 4).toFloat()
        val from = Offset(
            lamp.x + kotlin.math.cos(radians) * 0.105f * size.width,
            lamp.y + kotlin.math.sin(radians) * 0.105f * size.width,
        )
        val to = Offset(
            lamp.x + kotlin.math.cos(radians) * 0.145f * size.width,
            lamp.y + kotlin.math.sin(radians) * 0.145f * size.width,
        )
        drawLine(MeasureColours.Accent, from, to, hairline, cap = StrokeCap.Round)
    }
}

/** A phone sweeping, with the trail it should leave: wide, even, unhurried. */
private fun DrawScope.drawSlow(hairline: Float, line: Float) {
    val centre = at(0.5f, 0.86f)

    for ((index, radius) in listOf(0.30f, 0.38f, 0.46f).withIndex()) {
        drawArc(
            color = if (index == 1) MeasureColours.Accent else MeasureColours.Line,
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(centre.x - radius * size.width, centre.y - radius * size.width),
            size = Size(radius * 2 * size.width, radius * 2 * size.width),
            style = Stroke(if (index == 1) line else hairline),
        )
    }

    // The phone, upright and central, at the sweep's midpoint.
    val phoneWidth = 0.12f * size.width
    val phoneHeight = 0.20f * size.height
    drawRoundRect(
        color = MeasureColours.Ink,
        topLeft = Offset(centre.x - phoneWidth / 2f, centre.y - 0.46f * size.width - phoneHeight / 2f),
        size = Size(phoneWidth, phoneHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
        style = Stroke(line),
    )
}

/**
 * Where to put the reticle, and the two centimetres of skirting board that ruin it.
 *
 * Section rather than plan, because this is the one piece of advice that is about height.
 */
private fun DrawScope.drawJoint(hairline: Float, line: Float) {
    val wallFace = 0.26f
    val skirtingTop = 0.52f
    val floorLine = 0.66f

    val left = 0.12f
    val width = 0.76f * size.width

    // Wall, skirting, floor as three stacked bands. Distinct fills so the section reads
    // without labels: the wall is the ground the app is measuring to, the skirting is the
    // thing in front of it, the floor is what the reticle is projected onto.
    drawRect(MeasureColours.Surface, at(left, wallFace), Size(width, (skirtingTop - wallFace) * size.height))
    drawRect(MeasureColours.Line, at(left, skirtingTop), Size(width, (floorLine - skirtingTop) * size.height))
    drawRect(MeasureColours.Sunk, at(left, floorLine), Size(width, (0.86f - floorLine) * size.height))

    drawRect(
        color = MeasureColours.Ink,
        topLeft = at(left, wallFace),
        size = Size(width, (floorLine - wallFace) * size.height),
        style = Stroke(hairline),
    )
    drawLine(MeasureColours.Ink, at(left, skirtingTop), at(0.88f, skirtingTop), hairline)
    drawLine(MeasureColours.Ink, at(left, floorLine), at(0.88f, floorLine), line, cap = StrokeCap.Round)

    // Wrong: on the board.
    val wrong = at(0.32f, (skirtingTop + floorLine) / 2f)
    val tick = 0.026f
    for (sign in listOf(-1f, 1f)) {
        drawLine(
            MeasureColours.InkFaint,
            Offset(wrong.x - tick * size.width, wrong.y - sign * tick * size.height),
            Offset(wrong.x + tick * size.width, wrong.y + sign * tick * size.height),
            hairline,
        )
    }

    // Right: in the joint itself.
    val right = at(0.66f, floorLine)
    drawCircle(MeasureColours.Accent, 0.055f * size.width, right, style = Stroke(line))
    drawCircle(MeasureColours.Accent, 0.014f * size.width, right)
}
