package com.measure.feature.capture

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.measure.ar.CaptureMode
import com.measure.core.designsystem.MeasureButton
import com.measure.core.designsystem.MeasureColours
import com.measure.core.designsystem.MeasureSpace
import com.measure.core.geometry.capture.MeasurementMode

/**
 * The capture screen's controls — docs/PRODUCT_PLAN.md M10a, direction A.
 *
 * The one place in the app where chrome sits on a live camera image, so everything here is
 * either a bordered control against the feed or a slab with a dark ground behind it. The
 * containing pill that used to hold the segmented controls is gone: on a moving background
 * it read as a second surface competing with the slabs, where a row of bordered buttons
 * reads as controls on glass.
 *
 * **The selected state changed colour, and that is the point of this pass.** These rows
 * used `Ready` to mean "this mode is chosen", while `Ready` also means "a capture would be
 * accepted" on the reticle two centimetres away. Selection is interaction and now uses the
 * accent; `Ready` is reserved for what the measurement is doing.
 */

/** Free / Level / Plumb. Three states, so a segmented control beats a dropdown. */
@Composable
internal fun ModeSelector(
    selected: MeasurementMode,
    onSelect: (MeasurementMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(MeasureSpace.Tight)) {
        MeasurementMode.entries.forEach { mode ->
            MeasureButton(
                label = mode.label,
                onClick = { onSelect(mode) },
                selected = mode == selected,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Distance versus room. The top-level choice about what is being captured. */
@Composable
internal fun CaptureModeSelector(
    selected: CaptureMode,
    onSelect: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(MeasureSpace.Tight)) {
        CaptureMode.entries.forEach { mode ->
            MeasureButton(
                label = if (mode == CaptureMode.DISTANCE) "Distance" else "Room",
                onClick = { onSelect(mode) },
                selected = mode == selected,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The shutter.
 *
 * Deliberately large and central. Capture happens while the phone must stay still, and a
 * small target near an edge is exactly the thing that makes people move the phone as they
 * press it — which then fails the dispersion check they just triggered.
 *
 * Disabled rather than hidden when capture is gated, because a control that vanishes reads
 * as a bug and one that is visibly dimmed reads as a reason.
 *
 * **This is the one control that keeps the state colours**, and deliberately so: its face
 * is not saying "press me", it is saying whether a capture would be accepted right now and
 * whether a burst is in flight. That is `docs/ACCURACY.md` information, not interaction,
 * and it is the single most useful thing on the screen at the moment of pressing.
 */
@Composable
internal fun CaptureButton(
    enabled: Boolean,
    sampling: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(if (sampling) 0.88f else 1f, label = "shutter scale")

    Box(
        modifier = modifier
            .size(76.dp)
            .scale(scale)
            .alpha(if (enabled || sampling) 1f else 0.45f)
            .clip(CircleShape)
            .background(MeasureColours.Scrim)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(
                    when {
                        sampling -> MeasureColours.Sampling
                        enabled -> MeasureColours.Ready
                        else -> MeasureColours.Idle
                    },
                )
                .border(3.dp, Color.White.copy(alpha = 0.85f), CircleShape),
        )
    }
}

/**
 * A small action beside the shutter.
 *
 * Kept as a named function rather than call sites reaching for [MeasureButton] directly, so
 * that "the things that sit next to the shutter" stays one decision — but it no longer has
 * its own idea of what a control looks like.
 */
@Composable
internal fun PillButton(
    label: String,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MeasureButton(
        label = label,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        primary = highlighted,
    )
}
