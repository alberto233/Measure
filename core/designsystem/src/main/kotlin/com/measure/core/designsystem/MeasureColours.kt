package com.measure.core.designsystem

import androidx.compose.ui.graphics.Color
import com.measure.core.geometry.capture.TrackingQuality

/**
 * The overlay palette.
 *
 * Everything sits on a live camera image, which may be any colour and any brightness, so
 * two rules apply throughout: saturated hues rather than pastels, and a dark scrim behind
 * anything textual. There is no light variant — an AR overlay is always "dark theme"
 * because the background is the room, not the app.
 *
 * Shared rather than local to the capture screen because the project list draws the same
 * plans on the same dark chrome, and two palettes that are meant to match but are defined
 * twice do not stay matching.
 */
object MeasureColours {
    /** No surface under the reticle yet. */
    val Idle = Color(0xFFE8EAED)

    /** A surface is acquired and a capture would be accepted. */
    val Ready = Color(0xFF2ED3B7)

    /** Capture is gated — bad tracking, or too close. */
    val Blocked = Color(0xFFFF6B6B)

    /** A sample burst is in flight. */
    val Sampling = Color(0xFFFFD166)

    /** The app's own background, where there is no camera image behind the chrome. */
    val Surface = Color(0xFF101317)

    /** Opaque panel, for screens with no camera behind them. */
    val Panel = Color(0xFF1A1D21)

    val Scrim = Color(0xE01A1D21)
    val ScrimSoft = Color(0xB01A1D21)
    val OnScrim = Color(0xFFF5F7FA)
    val OnScrimMuted = Color(0xFFAAB2BD)

    val Warning = Color(0xFFFFB020)

    fun forQuality(quality: TrackingQuality): Color = when (quality) {
        TrackingQuality.GOOD -> Ready
        TrackingQuality.FAIR -> Sampling
        TrackingQuality.POOR -> Warning
        TrackingQuality.NONE -> Blocked
    }
}
