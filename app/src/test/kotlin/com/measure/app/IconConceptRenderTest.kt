package com.measure.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **Exploration harness, not a test.** Delete once an icon direction is chosen.
 *
 * It exists because judging an icon needs two things this project can do cheaply and
 * Figma's Starter plan cannot: many iterations, and a look at the result *small*. The
 * first launcher icon shipped wrong because nothing rendered it; the second was caught by
 * rendering it. This is the same loop, applied to picking a direction rather than to
 * checking one.
 *
 * Every concept is drawn at 512 px (the Play listing asset, where detail is affordable)
 * and at 48 px (a launcher tile, where it is not). A concept that only works at one of
 * those sizes is not a failure — it is an argument for shipping two assets that share a
 * motif, which is what detailed app icons generally do.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w900dp-h1400dp-xhdpi")
class IconConceptRenderTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * One contact sheet: every concept large, and the same four at launcher size beneath.
     *
     * A single `setContent` because the rule allows only one per test — and it turns out to
     * be the better arrangement anyway. The judgement being made is comparative, and the
     * only question that matters for the small row is which of them still resolves.
     */
    @Test
    fun `render every concept at both sizes`() {
        compose.setContent {
            Column(
                Modifier.background(Color(0xFFF4F5F7)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // 2 x 2 rather than a row of four: a single row overflowed the root and
                // silently clipped the last two concepts out of the picture entirely.
                // Ours, large enough to judge the drawing.
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    for ((_, art) in CONCEPTS) Tile(180.dp, art)
                }

                // The shelf: ours interleaved with the genre decoys, at the size a store
                // search result actually shows. Interleaved rather than grouped, because
                // grouping would tell the eye which is which before it decides.
                val shelf = listOf(
                    DECOYS[0], CONCEPTS[0], DECOYS[1],
                    CONCEPTS[1], DECOYS[2], CONCEPTS[2],
                )
                for (row in shelf.chunked(3)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        for ((_, art) in row) Tile(38.dp, art)
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/icon-concepts.png")
    }

    @Composable
    private fun Tile(side: androidx.compose.ui.unit.Dp, art: DrawScope.() -> Unit) {
        Box(
            Modifier
                .size(side)
                .clip(RoundedCornerShape(side * 0.22f))
                .background(INK),
        ) {
            Canvas(Modifier.size(side)) { art(this) }
        }
    }

    private companion object {
        val INK = Color(0xFF101114)
        val WHITE = Color(0xFFFFFFFF)
        val ACCENT = Color(0xFF2F6BFF)
        val DIM = Color(0xFF6B6D75)

        val CONCEPTS: List<Pair<String, DrawScope.() -> Unit>> = listOf(
            "A-plan" to { drawPlan() },
            "E-ruler" to { drawPlanWithRuler() },
            "F-arrows" to { drawPlanWithArrows() },
        )

        /**
         * My approximations of the genre, not anybody's actual logo.
         *
         * A control, not artwork. The question they answer is whether our candidates read
         * as the same *category* when they are sitting on a shelf together, which is the
         * only thing an icon has to do in a store search grid.
         */
        val DECOYS: List<Pair<String, DrawScope.() -> Unit>> = listOf(
            "G1-ruler-phone" to { drawRulerPhone() },
            "G2-tape" to { drawTapeMeasure() },
            "G3-ruler-house" to { drawRulerHouse() },
        )
    }
}

private fun DrawScope.at(x: Float, y: Float) = Offset(x * size.width, y * size.height)
private fun DrawScope.u(v: Float) = v * size.width

private val WHITE_ = Color(0xFFFFFFFF)
private val INK_ = Color(0xFF101114)
private val ACCENT_ = Color(0xFF2F6BFF)

private fun DrawScope.walls(l: Float, t: Float, r: Float, b: Float, w: Float) {
    drawRect(WHITE_, at(l, t), Size(u(r - l), u(w)))
    drawRect(WHITE_, at(l, b - w), Size(u(r - l), u(w)))
    drawRect(WHITE_, at(l, t), Size(u(w), u(b - t)))
    drawRect(WHITE_, at(r - w, t), Size(u(w), u(b - t)))
}

private fun DrawScope.cut(x0: Float, y0: Float, x1: Float, y1: Float) =
    drawRect(INK_, at(x0, y0), Size(u(x1 - x0), u(y1 - y0)))

/** The door swing arc — the one mark that makes a rectangle read as architecture. */
private fun DrawScope.doorInBottomWall(from: Float, to: Float, wallTop: Float, wallBottom: Float) {
    cut(from, wallTop, to, wallBottom)
    val span = to - from
    val stroke = u(0.014f)
    drawLine(ACCENT_, at(from, wallTop), at(from, wallTop - span), stroke * 1.4f)
    drawArc(
        color = ACCENT_, startAngle = 270f, sweepAngle = 90f, useCenter = false,
        topLeft = at(from - span, wallTop - span),
        size = Size(u(span * 2), u(span * 2)), style = Stroke(stroke),
    )
}

private fun DrawScope.windowInTopWall(from: Float, to: Float, wallTop: Float, wallBottom: Float) {
    cut(from, wallTop, to, wallBottom)
    val stroke = u(0.011f)
    for (y in listOf(wallTop, wallBottom)) drawLine(WHITE_, at(from, y), at(to, y), stroke)
}

private fun DrawScope.dimension(from: Float, to: Float, y: Float) {
    val stroke = u(0.014f)
    drawLine(ACCENT_, at(from, y), at(to, y), stroke, cap = StrokeCap.Round)
    for (x in listOf(from, to)) drawLine(ACCENT_, at(x, y - 0.042f), at(x, y + 0.042f), stroke)
}

/**
 * A ruler edge: a baseline with graduated ticks.
 *
 * The borrowed category signal. A ruler is pre-learned — it means "measuring" before
 * anybody has read a word — and unlike a phone silhouette it sits along one edge instead
 * of taking the middle of the tile away from the thing that makes us different.
 */
private fun DrawScope.rulerEdge(from: Float, to: Float, y: Float, divisions: Int = 8) {
    val stroke = u(0.016f)
    drawLine(WHITE_, at(from, y), at(to, y), stroke, cap = StrokeCap.Round)
    for (i in 0..divisions) {
        val x = from + (to - from) * i / divisions
        val long = i % 2 == 0
        drawLine(
            color = if (long) ACCENT_ else WHITE_,
            start = at(x, y),
            end = at(x, y + if (long) 0.075f else 0.045f),
            strokeWidth = stroke * if (long) 1.0f else 0.7f,
        )
    }
}

/** A dimension run with real arrowheads rather than ticks. */
private fun DrawScope.dimensionArrows(from: Float, to: Float, y: Float) {
    val stroke = u(0.018f)
    drawLine(ACCENT_, at(from, y), at(to, y), stroke, cap = StrokeCap.Round)
    for ((x, dir) in listOf(from to 1f, to to -1f)) {
        val head = Path().apply {
            moveTo(at(x, y).x, at(x, y).y)
            lineTo(at(x + dir * 0.085f, y - 0.052f).x, at(x + dir * 0.085f, y - 0.052f).y)
            lineTo(at(x + dir * 0.085f, y + 0.052f).x, at(x + dir * 0.085f, y + 0.052f).y)
            close()
        }
        drawPath(head, ACCENT_)
    }
}

/** Baseline: the plan that read best last round. */
private fun DrawScope.drawPlan() {
    val l = 0.15f; val r = 0.85f; val t = 0.16f; val b = 0.72f; val w = 0.055f
    walls(l, t, r, b, w)
    doorInBottomWall(0.32f, 0.52f, b - w, b)
    windowInTopWall(0.56f, 0.78f, t, t + w)
    dimension(l, r, 0.86f)
}

/** The plan, with the category signal borrowed: a graduated ruler along the bottom. */
private fun DrawScope.drawPlanWithRuler() {
    val l = 0.15f; val r = 0.85f; val t = 0.12f; val b = 0.64f; val w = 0.055f
    walls(l, t, r, b, w)
    doorInBottomWall(0.30f, 0.48f, b - w, b)
    windowInTopWall(0.56f, 0.78f, t, t + w)
    rulerEdge(l, r, 0.80f)
}

/** The plan, with the measurement said louder: arrowheads instead of ticks. */
private fun DrawScope.drawPlanWithArrows() {
    val l = 0.15f; val r = 0.85f; val t = 0.16f; val b = 0.70f; val w = 0.055f
    walls(l, t, r, b, w)
    doorInBottomWall(0.32f, 0.52f, b - w, b)
    windowInTopWall(0.56f, 0.78f, t, t + w)
    dimensionArrows(l, r, 0.85f)
}

// --- genre decoys: crude on purpose, and nobody's actual logo -----------------------

private fun DrawScope.drawRulerPhone() {
    val stroke = u(0.028f)
    drawRoundRect(
        color = WHITE_, topLeft = at(0.28f, 0.10f), size = Size(u(0.44f), u(0.80f)),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(u(0.10f), u(0.10f)),
        style = Stroke(stroke),
    )
    drawLine(ACCENT_, at(0.20f, 0.72f), at(0.80f, 0.28f), stroke * 1.4f, cap = StrokeCap.Round)
    for (i in 1..4) {
        val f = i / 5f
        val x = 0.20f + 0.60f * f; val y = 0.72f - 0.44f * f
        drawLine(ACCENT_, at(x, y), at(x - 0.05f, y - 0.07f), stroke * 0.6f)
    }
}

private fun DrawScope.drawTapeMeasure() {
    val stroke = u(0.030f)
    drawRoundRect(
        color = WHITE_, topLeft = at(0.14f, 0.42f), size = Size(u(0.44f), u(0.42f)),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(u(0.10f), u(0.10f)),
        style = Stroke(stroke),
    )
    drawCircle(WHITE_, u(0.075f), at(0.36f, 0.63f), style = Stroke(stroke * 0.7f))
    val tape = Path().apply {
        moveTo(at(0.58f, 0.50f).x, at(0.58f, 0.50f).y)
        quadraticBezierTo(at(0.82f, 0.34f).x, at(0.82f, 0.34f).y, at(0.86f, 0.16f).x, at(0.86f, 0.16f).y)
    }
    drawPath(tape, ACCENT_, style = Stroke(stroke * 1.2f))
}

private fun DrawScope.drawRulerHouse() {
    val stroke = u(0.030f)
    val house = Path().apply {
        moveTo(at(0.50f, 0.14f).x, at(0.50f, 0.14f).y)
        lineTo(at(0.84f, 0.42f).x, at(0.84f, 0.42f).y)
        lineTo(at(0.84f, 0.70f).x, at(0.84f, 0.70f).y)
        lineTo(at(0.16f, 0.70f).x, at(0.16f, 0.70f).y)
        lineTo(at(0.16f, 0.42f).x, at(0.16f, 0.42f).y)
        close()
    }
    drawPath(house, WHITE_, style = Stroke(stroke))
    drawLine(ACCENT_, at(0.16f, 0.84f), at(0.84f, 0.84f), stroke, cap = StrokeCap.Round)
    for (i in 0..4) {
        val x = 0.16f + 0.68f * i / 4f
        drawLine(ACCENT_, at(x, 0.84f), at(x, 0.90f), stroke * 0.7f)
    }
}
