package com.traza.core.designsystem

import androidx.compose.ui.graphics.Color
import com.traza.core.geometry.capture.TrackingQuality

/**
 * The palette — see `docs/DESIGN.md`, direction 01 "Drafting".
 *
 * White, structured by hairlines rather than by fills or shadows, with a **black** primary
 * action so that blue is free to mean one thing only: this is the option you chose.
 *
 * **Three families, and they must not be confused.**
 *
 * [Primary] means *do this*. [Accent] means *this is currently selected*. Collapsing the two
 * is what the previous direction did — one orange meaning both — and it left the app with no
 * way to say "this is a button" that did not also say "and it is turned on".
 *
 * [Idle], [Ready], [Sampling], [Warning] and [Blocked] mean *this is how good the
 * measurement is*. They come from `docs/ACCURACY.md`, they appear on the reticle, the
 * tracking chip and the plan, and they are the one thing this app claims. Nothing
 * decorative may borrow them.
 *
 * And the **camera overlay stays dark** — see the scrim tokens below. That is not a
 * stylistic holdout; a light interface over a sunlit wall is absent rather than merely
 * low-contrast, and this app has shipped that fault once already.
 */
object MeasureColours {

    // --- surfaces -----------------------------------------------------------------

    /** The ground everything sits on. */
    val Surface = Color(0xFFFFFFFF)

    /**
     * A raised card: a sheet, a banner, a list row.
     *
     * The same white as [Surface] on purpose. In this direction separation comes from a
     * hairline, not from a fill — which means anything using this **must** also carry a
     * [Line] border or it will have no edge at all.
     */
    val Panel = Color(0xFFFFFFFF)

    /** A recess: text fields, secondary buttons, thumbnails. */
    val Sunk = Color(0xFFF6F7F8)

    /** Hairlines. This direction's only structural device. */
    val Line = Color(0xFFE4E5E9)

    // --- ink ----------------------------------------------------------------------

    /** Anything that is read first. */
    val Ink = Color(0xFF101114)

    /** Supporting text. */
    val InkMuted = Color(0xFF6B6D75)

    /** Eyebrows, units, placeholders. */
    val InkFaint = Color(0xFF9A9CA3)

    // --- action -------------------------------------------------------------------

    /** The one filled button on a screen. Black, so that blue can mean something else. */
    val Primary = Color(0xFF101114)

    val OnPrimary = Color(0xFFFFFFFF)

    /** Chosen: a selected chip, a selected segment, a focused field. */
    val Accent = Color(0xFF2F6BFF)

    val OnAccent = Color(0xFFFFFFFF)

    /** The fill behind a selected chip — the accent at wash strength. */
    val AccentWash = Color(0xFFEAF1FF)

    // --- the camera overlay, which stays dark --------------------------------------
    //
    // Capture-only. Reaching for OnScrim on a white screen is the mistake this split
    // exists to make impossible: before it, one pair of tokens meant both "text on the
    // camera" and "text on a panel", and the two stopped being the same colour the moment
    // the app went light.

    /**
     * The slab behind anything textual over the camera.
     *
     * Nearly opaque, and it has to be. At 72% over a sunlit wall this composites to a mid
     * grey, and amber advice text on mid grey is the same fault as amber on white — the
     * slab is what makes the overlay legible, so it cannot be the thing that is subtle.
     */
    val Scrim = Color(0xE6101114)

    /** A lighter slab, where the image should still read through. */
    val ScrimSoft = Color(0xC2101114)

    /** Text on the scrim. */
    val OnScrim = Color(0xFFF4F5F7)

    /** Supporting text on the scrim. */
    val OnScrimMuted = Color(0xFFB9BCC4)

    // --- measurement state — see docs/ACCURACY.md ----------------------------------
    //
    // Retuned so each value is legible on white *and* on the dark scrim, because several
    // appear in both places: Warning marks a subtotal in the editor and an aim problem
    // over the camera, and it has to survive both grounds.

    /** No surface under the reticle yet. **Overlay only** — it is near-white by design. */
    val Idle = Color(0xFFF4F5F7)

    /** A surface is acquired and a capture would be accepted. */
    val Ready = Color(0xFF12A06F)

    /** Capture is gated — bad tracking, or too close. */
    val Blocked = Color(0xFFE0483C)

    /** A sample burst is in flight. */
    val Sampling = Color(0xFFE08A0B)

    /** Stated caution: a subtotal, a derived distance, an arrangement nobody measured. */
    val Warning = Color(0xFFC2700D)

    fun forQuality(quality: TrackingQuality): Color = when (quality) {
        TrackingQuality.GOOD -> Ready
        TrackingQuality.FAIR -> Sampling
        TrackingQuality.POOR -> Warning
        TrackingQuality.NONE -> Blocked
    }
}
