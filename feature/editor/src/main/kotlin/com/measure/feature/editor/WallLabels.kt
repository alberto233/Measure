package com.measure.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.measure.core.data.SavedRoom
import com.measure.core.designsystem.MeasureColours

/**
 * Wall lengths, drawn as composables positioned over the canvas.
 *
 * Text on a `Canvas` means either `drawText` with a `TextMeasurer` or a font atlas;
 * placing real `Text` composables at computed offsets is less code, gets font scaling and
 * locale digits for free, and matches how the AR overlay does the same job.
 *
 * A locked wall is marked, because the distinction matters: an unlocked length is what
 * the camera estimated, a locked one is what the user measured with a tape and is
 * therefore the number the rest of the plan was fitted around.
 */
@Composable
internal fun WallLabels(
    rooms: List<SavedRoom>,
    camera: PlanCamera,
    size: IntSize,
    dragging: EditorViewModel.DragState?,
    selection: Selection,
    formatLength: (Double) -> String,
) {
    if (size == IntSize.Zero) return

    data class Placed(val text: String, val x: Float, val y: Float, val locked: Boolean, val selected: Boolean)

    val placed = buildList {
        rooms.forEach { room ->
            val outline = room.outline.toMutableList()
            if (dragging?.roomId == room.id && dragging.index in outline.indices) {
                outline[dragging.index] = dragging.position
            }
            if (outline.size < 3) return@forEach

            outline.indices.forEach { index ->
                val from = outline[index]
                val to = outline[(index + 1) % outline.size]
                val length = from.distanceTo(to)
                // Below this a label is longer than the wall it belongs to and only adds
                // clutter; the wall is still selectable and its length shows in the panel.
                if (length < MINIMUM_LABELLED_METRES) return@forEach

                val midpoint = (from + to) * 0.5
                val screen = camera.toScreen(midpoint, size)
                add(
                    Placed(
                        text = formatLength(length),
                        x = screen.x,
                        y = screen.y,
                        locked = room.lockedLengths.containsKey(index),
                        selected = selection == Selection.Wall(room.id, index),
                    ),
                )
            }
        }
    }

    if (placed.isEmpty()) return

    Layout(
        content = {
            placed.forEach { label ->
                Text(
                    text = if (label.locked) "${label.text} ✓" else label.text,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MeasureColours.Scrim)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    color = when {
                        label.selected -> MeasureColours.Sampling
                        label.locked -> MeasureColours.Ready
                        else -> MeasureColours.OnScrim
                    },
                    fontSize = 12.sp,
                    fontWeight = if (label.locked) FontWeight.Bold else FontWeight.Medium,
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val label = placed.getOrNull(index) ?: return@forEachIndexed
                placeable.place(
                    x = (label.x - placeable.width / 2f).toInt(),
                    y = (label.y - placeable.height / 2f).toInt(),
                )
            }
        }
    }
}

/** Walls shorter than this get no label; it would be wider than the wall. */
private const val MINIMUM_LABELLED_METRES = 0.25
