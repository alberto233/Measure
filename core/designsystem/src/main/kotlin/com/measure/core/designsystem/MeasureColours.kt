package com.measure.core.designsystem

import androidx.compose.ui.graphics.Color
import com.measure.core.geometry.capture.TrackingQuality

/**
 * The palette — docs/PRODUCT_PLAN.md M10a, direction A.
 *
 * Near-black, high contrast, one signal colour. Everything sits on a live camera image on
 * the capture screen, which may be any colour and any brightness, so two rules apply
 * throughout: saturated hues rather than pastels, and a dark slab behind anything textual.
 * There is no light variant — an AR overlay is always dark, because the background is the
 * room and not the app. The **exports** are light on white and own their own palette, in
 * `PlanDrawing` and `SvgExporter`; they get printed, and a dark drawing comes out of a
 * printer as a page of toner.
 *
 * **Two families, and they must not be confused.**
 *
 * [Accent] means *you can act on this*. It is the only interactive colour.
 *
 * [Idle], [Ready], [Sampling], [Warning] and [Blocked] mean *this is how good the
 * measurement is*. They come from `docs/ACCURACY.md`, they appear on the reticle, the
 * tracking chip and the plan, and they are the one thing this app claims. Nothing
 * decorative may borrow them.
 *
 * That separation is the point of this revision. `Ready` previously meant good tracking,
 * selected, interactive **and** reference text, all at once — which left the app with no
 * way to say "this is a button" that did not also say "the measurement is good".
 */
object MeasureColours {

    // --- ground -------------------------------------------------------------------

    /** The app's own background, where there is no camera image behind the chrome. */
    val Surface = Color(0xFF0B0B0C)

    /** A raised slab: panels, sheets, and anything textual over the camera. */
    val Panel = Color(0xFF141416)

    /** Hairlines. Structure comes from rules in this direction, not from fills. */
    val Line = Color(0xFF2A2A2E)

    val Scrim = Color(0xE60B0B0C)
    val ScrimSoft = Color(0xB3141416)

    val OnScrim = Color(0xFFF2F2F0)
    val OnScrimMuted = Color(0xFF8A8A90)

    // --- interaction --------------------------------------------------------------

    /**
     * The one interactive colour. Signal orange, borrowed from instrument panels, where it
     * has always meant "this is the control that does the thing".
     */
    val Accent = Color(0xFFFF4A1C)

    /** Text and iconography sitting on [Accent]. */
    val OnAccent = Color(0xFF0B0B0C)

    // --- measurement state — see docs/ACCURACY.md ----------------------------------

    /** No surface under the reticle yet. */
    val Idle = Color(0xFFE8EAED)

    /** A surface is acquired and a capture would be accepted. */
    val Ready = Color(0xFF3DDC9D)

    /** Capture is gated — bad tracking, or too close. */
    val Blocked = Color(0xFFFF5A5A)

    /** A sample burst is in flight. */
    val Sampling = Color(0xFFFFC94D)

    val Warning = Color(0xFFFFA92E)

    fun forQuality(quality: TrackingQuality): Color = when (quality) {
        TrackingQuality.GOOD -> Ready
        TrackingQuality.FAIR -> Sampling
        TrackingQuality.POOR -> Warning
        TrackingQuality.NONE -> Blocked
    }
}
