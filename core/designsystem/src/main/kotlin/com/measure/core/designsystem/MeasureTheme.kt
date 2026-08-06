package com.measure.core.designsystem

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The type scale — docs/PRODUCT_PLAN.md M10a, direction A.
 *
 * Seven sizes, replacing the twelve bare literals that were spread across 87 usages. Every
 * one earns its place by doing a job no neighbour does; anything that wanted an eighth is
 * an argument for changing one of these rather than adding to them.
 *
 * Numbers are monospaced. That is not decoration in a measuring app: `4.20` and `11.85`
 * have to occupy the same width or a column of them shivers as they update, and this app
 * updates them live while somebody walks a room. Tabular figures come free with a
 * monospaced face, and the face itself is what makes a reading look like an instrument's
 * rather than a paragraph's.
 *
 * [Mono] is the system monospace for now. Bundling JetBrains Mono, which is what the design
 * direction actually specifies, adds a font file to the APK and belongs in the polish pass
 * rather than in a change this wide.
 */
object MeasureType {

    private val Mono = FontFamily.Monospace

    /** Screen titles. One per screen, at the top, and nowhere else. */
    val Display = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em)

    /** Section and panel headings. */
    val Title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)

    /** Anything read as a sentence. */
    val Body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal)

    /** Controls, list entries, the things that are read as objects rather than prose. */
    val Label = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)

    /** Secondary detail. Below this, text stops being readable at arm's length. */
    val Small = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal)

    /**
     * The micro-label: uppercase, letterspaced, quiet.
     *
     * The signature of this direction. Every value on screen is introduced by one of these,
     * which is what makes a screen read as an instrument rather than as a form — the label
     * recedes, the number does the talking.
     */
    val Tag = TextStyle(
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.12.em,
    )

    /** A measurement, at the size a measurement deserves. */
    val Reading = TextStyle(
        fontFamily = Mono,
        fontSize = 44.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.01).em,
    )

    /** A measurement in a list or a panel, rather than the one being taken. */
    val Value = TextStyle(fontFamily = Mono, fontSize = 20.sp, fontWeight = FontWeight.Medium)

    /** A measurement small enough to sit inline. */
    val ValueSmall = TextStyle(fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

/**
 * The spacing scale.
 *
 * Six steps. The point of a scale is not that these particular numbers are correct — it is
 * that a layout built from six values reads as deliberate and one built from every even
 * number between 4 and 24 reads as accreted, which is what this app had.
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
 * Nearly square, deliberately. Direction A is hard-edged: a 2 dp radius reads as a cut
 * corner rather than a rounded one, which is the difference between an instrument and a
 * consumer app. [Pill] exists only for controls that are genuinely capsule-shaped.
 */
object MeasureShape {
    val Edge: Dp = 2.dp
    val Panel: Dp = 4.dp
    val Pill: Dp = 100.dp
}
