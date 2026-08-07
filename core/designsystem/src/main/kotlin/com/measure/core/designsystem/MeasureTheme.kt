package com.measure.core.designsystem

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The type scale — see `docs/DESIGN.md`, direction 01.
 *
 * Nine roles, each earning its place by doing a job no neighbour does. Anything that wants a
 * tenth is an argument for changing one of these rather than adding to them.
 *
 * **The system face, deliberately.** For a direction that is explicitly Apple-like, the
 * platform's own face is the correct answer rather than a compromise, and it costs nothing
 * in APK size. The previous direction bundled nothing either but *specified* JetBrains Mono
 * and never shipped it, which is the worst of both.
 *
 * **Numbers are tabular, not monospaced.** `4.20` and `11.85` still have to occupy the same
 * width — this app updates figures live while somebody walks a room, and a column that
 * shivers is unreadable. But a monospaced *face* was the technical direction's signature.
 * The `tnum` feature gives the alignment without the typewriter.
 */
object MeasureType {

    /** Lining, fixed-width digits in a proportional face. */
    private const val TABULAR = "tnum"

    /** One screen title, at the top, and nowhere else. */
    val Display = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.028).em)

    /** Section and sheet headings. */
    val Title = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em)

    /** Anything read as a sentence. */
    val Body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal)

    /** Secondary controls and list entries — things read as objects rather than prose. */
    val Label = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.Medium)

    /** Supporting detail. Below this, text stops being readable at arm's length. */
    val Small = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Normal)

    /**
     * The micro-eyebrow over a value. The one place uppercase survives.
     *
     * Uppercasing a small semibold letterspaced label is a typographic device; uppercasing
     * every button label is a voice, and it was the wrong one. See [MeasureTag], which is
     * the only thing that applies the transform.
     */
    val Tag = TextStyle(
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.em,
    )

    /** The measurement being taken, at the size a measurement deserves. */
    val Reading = TextStyle(
        fontSize = 34.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.035).em,
        fontFeatureSettings = TABULAR,
    )

    /** A measurement in a list or a panel, rather than the one being taken. */
    val Value = TextStyle(
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.02).em,
        fontFeatureSettings = TABULAR,
    )

    /** A measurement small enough to sit inline. */
    val ValueSmall = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = TABULAR,
    )
}

/**
 * The spacing scale.
 *
 * Six steps. The point is not that these particular numbers are correct — it is that a
 * layout built from six values reads as deliberate and one built from every even number
 * between 4 and 24 reads as accreted, which is what this app had.
 */
object MeasureSpace {
    val Hair: Dp = 4.dp
    val Tight: Dp = 8.dp
    val Snug: Dp = 12.dp
    val Base: Dp = 16.dp
    val Loose: Dp = 20.dp
    val Wide: Dp = 24.dp
}

/**
 * Corner radii.
 *
 * Properly rounded, where the previous direction was hard-edged at 2dp. The 2dp radius read
 * as a cut corner, which is exactly the machined look that was rejected.
 */
object MeasureShape {
    /** Buttons, fields, thumbnails. */
    val Edge: Dp = 10.dp

    /** Cards, sheets, banners. */
    val Panel: Dp = 14.dp

    /** Chips and segmented controls, which are genuinely capsule-shaped. */
    val Pill: Dp = 999.dp
}
