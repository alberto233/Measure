package com.measure.feature.capture

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.measure.ar.CaptureMode
import com.measure.core.geometry.capture.MeasurementMode

/** Free / Level / Plumb. Three states, so a segmented control beats a dropdown. */
@Composable
internal fun ModeSelector(
    selected: MeasurementMode,
    onSelect: (MeasurementMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(CaptureColours.Scrim)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MeasurementMode.entries.forEach { mode ->
            val active = mode == selected
            Text(
                text = mode.label,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) CaptureColours.Ready else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = if (active) Color(0xFF06231F) else CaptureColours.OnScrimMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
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
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(CaptureColours.Scrim)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CaptureMode.entries.forEach { mode ->
            val active = mode == selected
            Text(
                text = if (mode == CaptureMode.DISTANCE) "Distance" else "Room",
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) CaptureColours.OnScrim else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                color = if (active) Color(0xFF14181C) else CaptureColours.OnScrimMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
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
 * Disabled rather than hidden when capture is gated, because a control that vanishes
 * reads as a bug and a control that is visibly dimmed reads as a reason.
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
            .size(74.dp)
            .scale(scale)
            .alpha(if (enabled || sampling) 1f else 0.45f)
            .clip(CircleShape)
            .background(CaptureColours.Scrim)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (sampling) CaptureColours.Sampling else CaptureColours.Ready)
                .border(3.dp, Color.White.copy(alpha = 0.85f), CircleShape),
        )
    }
}

/** A small pill action. Text rather than an icon: no icon dependency, and no ambiguity. */
@Composable
internal fun PillButton(
    label: String,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(50))
            .background(if (highlighted) CaptureColours.Sampling else CaptureColours.Scrim)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        color = if (highlighted) Color(0xFF2A1F00) else CaptureColours.OnScrim,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}
