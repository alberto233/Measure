package com.measure.feature.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.measure.core.geometry.capture.HitSource
import com.measure.core.geometry.capture.RangeAdvice
import com.measure.core.geometry.capture.TrackingIssue
import com.measure.core.geometry.capture.TrackingStatus

/**
 * The live tracking-quality readout — docs/ACCURACY.md M5.
 *
 * The important part is that it names the *problem*, not just the severity. "Poor
 * tracking" tells the user nothing; "Too dark — turn a light on" and "Slow down" have
 * opposite remedies, and the difference between them is the difference between a user who
 * fixes the shot and one who decides the app is broken.
 */
@Composable
internal fun TrackingChip(
    tracking: TrackingStatus,
    depthEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colour = CaptureColours.forQuality(tracking.quality)
    val text = if (tracking.issue == TrackingIssue.NONE) {
        "Tracking ${tracking.quality.name.lowercase()}"
    } else {
        tracking.issue.advice
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(CaptureColours.Scrim)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(colour))
        Text(
            text = text,
            color = CaptureColours.OnScrim,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        // Worth surfacing: with depth, hit tests work on surfaces ARCore has not yet
        // fitted a plane to, which is most of the room for the first few seconds.
        if (depthEnabled) {
            Text("· depth", color = CaptureColours.OnScrimMuted, fontSize = 12.sp)
        }
    }
}

/**
 * Range and surface-quality advice for the current aim — docs/ACCURACY.md M1 and M4.
 *
 * Silent in the comfortable case. An indicator that is always lit stops being read.
 */
@Composable
internal fun AimAdvice(
    advice: RangeAdvice,
    source: HitSource?,
    rangeText: String?,
    modifier: Modifier = Modifier,
) {
    val lines = buildList {
        advice.message?.let { add(it to CaptureColours.Warning) }
        if (source != null && !source.isStructural) {
            add("Reading from ${source.label}" to CaptureColours.OnScrimMuted)
        }
        if (rangeText != null && advice == RangeAdvice.IDEAL && isEmpty()) {
            add(rangeText to CaptureColours.OnScrimMuted)
        }
    }
    if (lines.isEmpty()) return

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CaptureColours.ScrimSoft)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        lines.forEach { (text, colour) ->
            Text(text, color = colour, fontSize = 13.sp)
        }
    }
}

/** A measurement value rendered over the camera image, at a projected world position. */
@Composable
internal fun MeasurementLabel(
    text: String,
    emphasised: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (emphasised) CaptureColours.Scrim else CaptureColours.ScrimSoft)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = if (emphasised) CaptureColours.Ready else CaptureColours.OnScrim,
        fontSize = if (emphasised) 16.sp else 14.sp,
        fontWeight = FontWeight.SemiBold,
    )
}
