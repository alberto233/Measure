package com.measure.feature.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.measure.core.designsystem.MeasureColours
import com.measure.core.designsystem.PlanStyle
import com.measure.core.designsystem.PlanView
import com.measure.core.geometry.Vec2

/**
 * A live plan view of the room being captured.
 *
 * The AR view alone is a poor way to understand a room: the user is inside it, looking at
 * one wall at a time, and cannot see the shape they are making. This is the feedback that
 * turns a sequence of taps into something recognisable as a floor plan while there is
 * still time to fix it — a corner tapped in the wrong place is obvious here and invisible
 * through the camera.
 *
 * The drawing itself is `:core:designsystem`'s, shared with the project list's thumbnails
 * so a room cannot look like one shape here and another there.
 */
@Composable
internal fun RoomMinimap(
    corners: List<Vec2>,
    preview: Vec2?,
    closed: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
) {
    if (corners.isEmpty()) return

    PlanView(
        outlines = listOf(corners),
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(MeasureColours.Scrim),
        closed = closed,
        preview = preview,
        showClosingHint = !closed,
        style = PlanStyle(filled = closed, markStart = !closed),
    )
}
