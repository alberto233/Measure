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
@Config(qualifiers = "xhdpi")
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
                for (pair in CONCEPTS.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        for ((_, art) in pair) Tile(132.dp, art)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    for ((_, art) in CONCEPTS) Tile(24.dp, art)
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
            "B-two-room" to { drawTwoRoomPlan() },
            "C-phone" to { drawPhoneAndPlan() },
            "D-iso-room" to { drawIsoRoom() },
        )
    }
}

private fun DrawScope.at(x: Float, y: Float) = Offset(x * size.width, y * size.height)
private fun DrawScope.u(v: Float) = v * size.width

private val WHITE_ = Color(0xFFFFFFFF)
private val INK_ = Color(0xFF101114)
private val ACCENT_ = Color(0xFF2F6BFF)

/** Four wall bands, drawn as a plan cuts them: solid poché with square corners. */
private fun DrawScope.walls(l: Float, t: Float, r: Float, b: Float, w: Float) {
    drawRect(WHITE_, at(l, t), Size(u(r - l), u(w)))
    drawRect(WHITE_, at(l, b - w), Size(u(r - l), u(w)))
    drawRect(WHITE_, at(l, t), Size(u(w), u(b - t)))
    drawRect(WHITE_, at(r - w, t), Size(u(w), u(b - t)))
}

/** Knocks a hole in a wall. Everything else is drawn into the hole. */
private fun DrawScope.cut(x0: Float, y0: Float, x1: Float, y1: Float) =
    drawRect(INK_, at(x0, y0), Size(u(x1 - x0), u(y1 - y0)))

/**
 * A door: the gap, the leaf standing open, and the arc it sweeps.
 *
 * The single most legible thing in this whole exploration. A rectangle is a box; a
 * rectangle with a swing arc is a *floor plan*, and almost everybody recognises it without
 * being able to say why.
 */
private fun DrawScope.doorInBottomWall(from: Float, to: Float, wallTop: Float, wallBottom: Float) {
    cut(from, wallTop, to, wallBottom)
    val span = to - from
    val stroke = u(0.014f)
    drawLine(ACCENT_, at(from, wallTop), at(from, wallTop - span), stroke * 1.4f)
    drawArc(
        color = ACCENT_,
        startAngle = 270f,
        sweepAngle = 90f,
        useCenter = false,
        topLeft = at(from - span, wallTop - span),
        size = Size(u(span * 2), u(span * 2)),
        style = Stroke(stroke),
    )
}

/**
 * A window: the gap, with glazing drawn on both wall faces.
 *
 * The first version put one line down the middle of the gap, which read as a detached
 * floating bar rather than as glass in a wall — a glitch, not a symbol. Two lines on the
 * faces is how a plan actually draws it, and it reads immediately.
 */
private fun DrawScope.windowInTopWall(from: Float, to: Float, wallTop: Float, wallBottom: Float) {
    cut(from, wallTop, to, wallBottom)
    val stroke = u(0.011f)
    for (y in listOf(wallTop, wallBottom)) {
        drawLine(WHITE_, at(from, y), at(to, y), stroke)
    }
}

/** A dimension string: the run, and a tick at each end. */
private fun DrawScope.dimension(from: Float, to: Float, y: Float) {
    val stroke = u(0.014f)
    drawLine(ACCENT_, at(from, y), at(to, y), stroke, cap = StrokeCap.Round)
    for (x in listOf(from, to)) {
        drawLine(ACCENT_, at(x, y - 0.042f), at(x, y + 0.042f), stroke)
    }
}

/** One room, dimensioned: the cleaned-up version of the strongest first-round concept. */
private fun DrawScope.drawPlan() {
    val l = 0.15f; val r = 0.85f; val t = 0.16f; val b = 0.72f; val w = 0.055f
    walls(l, t, r, b, w)
    doorInBottomWall(0.32f, 0.52f, b - w, b)
    windowInTopWall(0.56f, 0.78f, t, t + w)
    dimension(l, r, 0.86f)
}

/**
 * Two rooms and the wall between them — a plan rather than a room.
 *
 * The richer answer to "hard to interpret": one rectangle is ambiguous, but a partition
 * with a door through it is unmistakably a *building*. It costs legibility at launcher
 * size, which is the trade this concept exists to show.
 */
private fun DrawScope.drawTwoRoomPlan() {
    val l = 0.12f; val r = 0.88f; val t = 0.14f; val b = 0.70f; val w = 0.05f
    walls(l, t, r, b, w)

    // The partition, with a doorway through it.
    val px = 0.54f
    drawRect(WHITE_, at(px, t), Size(u(w), u(b - t)))
    cut(px, 0.40f, px + w, 0.56f)
    // Hinged at the top of the opening, swinging into the right-hand room. The first
    // version hung it from the bottom and swept the arc the wrong way, which left the leaf
    // and the arc visibly detached from each other.
    val stroke = u(0.012f)
    val hinge = 0.40f
    val leaf = 0.16f
    drawLine(ACCENT_, at(px + w, hinge), at(px + w + leaf, hinge), stroke * 1.3f)
    drawArc(
        color = ACCENT_,
        startAngle = 0f,
        sweepAngle = 90f,
        useCenter = false,
        topLeft = at(px + w - leaf, hinge - leaf),
        size = Size(u(leaf * 2), u(leaf * 2)),
        style = Stroke(stroke),
    )

    windowInTopWall(0.20f, 0.42f, t, t + w)
    windowInTopWall(0.66f, 0.80f, t, t + w)
    dimension(l, px + w, 0.84f)
    dimension(px + w, r, 0.84f)
}

/** A phone with a real plan on it, and the reticle on the corner being taken. */
private fun DrawScope.drawPhoneAndPlan() {
    val l = 0.24f; val r = 0.76f; val t = 0.10f; val b = 0.90f
    val stroke = u(0.022f)

    drawRoundRect(
        color = WHITE_,
        topLeft = at(l, t),
        size = Size(u(r - l), u(b - t)),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(u(0.09f), u(0.09f)),
        style = Stroke(stroke),
    )

    // A plan on the screen — walls with thickness and a door, not a plain rectangle.
    val pl = 0.32f; val pr = 0.68f; val pt = 0.32f; val pb = 0.62f; val pw = 0.035f
    walls(pl, pt, pr, pb, pw)
    doorInBottomWall(0.42f, 0.55f, pb - pw, pb)

    // The corner being aimed at.
    drawCircle(WHITE_, u(0.052f), at(pr - pw / 2, pt + pw / 2), style = Stroke(u(0.014f)))
    drawCircle(ACCENT_, u(0.020f), at(pr - pw / 2, pt + pw / 2))
}

/**
 * A room as a volume, with a door on the floor so it reads as a room and not a cube.
 *
 * The first version was a handsome wireframe box that said nothing about buildings. The
 * door arc and the dimensioned base edge are what turn it into a room.
 */
private fun DrawScope.drawIsoRoom() {
    val cx = 0.5f; val cy = 0.56f
    val run = 0.32f; val rise = 0.16f; val h = 0.24f
    val stroke = u(0.014f)
    fun p(dx: Float, dy: Float) = at(cx + dx, cy + dy)

    val near = p(0f, h / 2 + rise)
    val left = p(-run, h / 2)
    val right = p(run, h / 2)
    val far = p(0f, h / 2 - rise)

    val floor = Path().apply {
        moveTo(near.x, near.y); lineTo(left.x, left.y)
        lineTo(far.x, far.y); lineTo(right.x, right.y); close()
    }
    drawPath(floor, ACCENT_.copy(alpha = 0.20f))
    drawPath(floor, WHITE_, style = Stroke(stroke))

    val tops = listOf(near, left, right, far).map { Offset(it.x, it.y - u(h)) }
    listOf(near, left, right).forEachIndexed { i, base -> drawLine(WHITE_, base, tops[i], stroke) }
    val ceiling = Path().apply {
        moveTo(tops[0].x, tops[0].y); lineTo(tops[1].x, tops[1].y)
        lineTo(tops[3].x, tops[3].y); lineTo(tops[2].x, tops[2].y); close()
    }
    drawPath(ceiling, WHITE_, style = Stroke(stroke))

    // A doorway on the near-right base edge, drawn flat on the floor plane.
    val doorA = Offset(near.x + (right.x - near.x) * 0.30f, near.y + (right.y - near.y) * 0.30f)
    val doorB = Offset(near.x + (right.x - near.x) * 0.62f, near.y + (right.y - near.y) * 0.62f)
    drawLine(INK_, doorA, doorB, stroke * 2.2f)
    drawLine(ACCENT_, doorA, Offset(doorA.x + u(0.10f), doorA.y - u(0.10f)), stroke * 1.2f)

    for (corner in listOf(near, left, right)) drawCircle(ACCENT_, u(0.028f), corner)
}
