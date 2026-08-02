package com.measure.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.measure.core.data.SavedRoom
import com.measure.core.designsystem.MeasureColours
import kotlinx.coroutines.delay

/**
 * The 2D plan editor — M5.
 *
 * Three things happen here, in rising order of value. Rooms can be looked at properly,
 * which the capture screen cannot do because you are standing inside them. Corners can be
 * nudged, because a corner behind a radiator gets tapped approximately. And a wall can be
 * given the length you measured with a tape — which is the one that matters, because the
 * solve pulls the entire room towards that single certain number rather than only fixing
 * the wall you typed it into.
 */
@Composable
fun EditorScreen(
    projectId: Long,
    onBack: () -> Unit,
    onAddRoom: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditorViewModel = viewModel(),
) {
    LaunchedEffect(projectId) { viewModel.load(projectId) }

    val project by viewModel.project.collectAsStateWithLifecycle()
    val rooms = project?.rooms.orEmpty()

    Box(modifier.fillMaxSize().background(MeasureColours.Surface)) {
        PlanCanvas(
            rooms = rooms,
            selection = viewModel.selection,
            dragging = viewModel.dragging,
            formatLength = viewModel::formatLength,
            onSelect = viewModel::select,
            onBeginDrag = viewModel::beginDrag,
            onDrag = viewModel::updateDrag,
            onEndDrag = viewModel::endDrag,
            modifier = Modifier.fillMaxSize(),
        )

        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(
                title = project?.name ?: "",
                subtitle = rooms.summarise(viewModel),
                onBack = onBack,
                onAddRoom = onAddRoom,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            if (rooms.isEmpty() && project != null) {
                EmptyPlan(Modifier.align(Alignment.Center))
            }

            SelectionPanel(viewModel, Modifier.align(Alignment.BottomCenter))

            viewModel.message?.let { text ->
                LaunchedEffect(text) {
                    delay(MESSAGE_DURATION_MS)
                    viewModel.dismissMessage()
                }
                Text(
                    text = text,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MeasureColours.Scrim)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    color = MeasureColours.Warning,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

private fun List<SavedRoom>.summarise(viewModel: EditorViewModel): String {
    if (isEmpty()) return "No rooms yet"
    val area = sumOf { it.area.squareMetres }
    return "$size ${if (size == 1) "room" else "rooms"} · " +
        com.measure.core.units.AreaFormatter.format(
            com.measure.core.units.Area(area),
            viewModel.unitSystem(),
        )
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onAddRoom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill("Back", onClick = onBack)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = MeasureColours.OnScrim, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MeasureColours.OnScrimMuted, fontSize = 12.sp)
        }
        Pill("Add room", onClick = onAddRoom)
    }
}

@Composable
private fun EmptyPlan(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Nothing to edit yet", color = MeasureColours.OnScrim, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Capture a room and it appears here",
            color = MeasureColours.OnScrimMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * What can be done with whatever is selected.
 *
 * Nothing selected shows the gesture hint instead. A plan with no visible affordances is
 * a plan people assume is read-only, and long-press-to-drag is not discoverable.
 */
@Composable
private fun SelectionPanel(viewModel: EditorViewModel, modifier: Modifier = Modifier) {
    val selection = viewModel.selection

    Column(
        modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MeasureColours.Scrim)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (selection) {
            Selection.None -> Text(
                text = "Pinch to zoom · tap a wall to set its true length · long-press a corner to move it",
                color = MeasureColours.OnScrimMuted,
                fontSize = 13.sp,
            )

            is Selection.Corner -> CornerPanel(viewModel, selection)
            is Selection.Wall -> WallPanel(viewModel, selection)
        }
    }
}

@Composable
private fun CornerPanel(viewModel: EditorViewModel, selection: Selection.Corner) {
    val room = viewModel.roomById(selection.roomId) ?: return

    Text(
        text = "${room.name} · corner ${selection.index + 1}",
        color = MeasureColours.OnScrim,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = "Long-press and drag to move it. The room re-solves when you let go, so " +
            "right angles and locked walls still hold.",
        color = MeasureColours.OnScrimMuted,
        fontSize = 12.sp,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Done", onClick = viewModel::clearSelection)
    }
}

@Composable
private fun WallPanel(viewModel: EditorViewModel, selection: Selection.Wall) {
    val room = viewModel.roomById(selection.roomId) ?: return
    val outline = room.outline
    if (outline.size < 3 || selection.index !in outline.indices) return

    val from = outline[selection.index]
    val to = outline[(selection.index + 1) % outline.size]
    val current = from.distanceTo(to)
    val locked = room.lockedLengths[selection.index]

    var typed by remember(selection, locked) {
        mutableStateOf(locked?.let { viewModel.formatLength(it) } ?: "")
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "${room.name} · wall ${selection.index + 1}",
                color = MeasureColours.OnScrim,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (locked != null) {
                    "Locked to ${viewModel.formatLength(locked)}"
                } else {
                    "Measured ${viewModel.formatLength(current)}"
                },
                color = if (locked != null) MeasureColours.Ready else MeasureColours.OnScrimMuted,
                fontSize = 12.sp,
            )
        }
        Text(viewModel.formatArea(room), color = MeasureColours.OnScrimMuted, fontSize = 12.sp)
    }

    Text(
        text = "Measured this wall with a tape? Type the true length — the whole room " +
            "tightens around it.",
        color = MeasureColours.OnScrimMuted,
        fontSize = 12.sp,
    )

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = typed,
            onValueChange = { typed = it },
            singleLine = true,
            textStyle = TextStyle(color = MeasureColours.OnScrim, fontSize = 16.sp),
            cursorBrush = SolidColor(MeasureColours.Ready),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MeasureColours.ScrimSoft)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        Pill(
            label = "Lock",
            enabled = typed.isNotBlank(),
            highlighted = true,
            onClick = { viewModel.lockWall(selection.roomId, selection.index, typed) },
        )
        if (locked != null) {
            Pill("Unlock", onClick = { viewModel.unlockWall(selection.roomId, selection.index) })
        }
    }
}

@Composable
private fun Pill(
    label: String,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        modifier = modifier
            .clip(CircleShape)
            .background(
                when {
                    !enabled -> MeasureColours.ScrimSoft
                    highlighted -> MeasureColours.Ready
                    else -> MeasureColours.ScrimSoft
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        color = when {
            !enabled -> MeasureColours.OnScrimMuted
            highlighted -> Color(0xFF06231F)
            else -> MeasureColours.OnScrim
        },
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}

private const val MESSAGE_DURATION_MS = 3000L
