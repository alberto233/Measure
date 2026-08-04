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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import com.measure.core.geometry.OpeningKind
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
    val measurements = project?.measurements.orEmpty()

    Box(modifier.fillMaxSize().background(MeasureColours.Surface)) {
        PlanCanvas(
            rooms = rooms,
            measurements = measurements,
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
                subtitle = summarise(rooms, measurements, viewModel),
                onBack = onBack,
                onAddRoom = onAddRoom,
                canUndo = viewModel.canUndo,
                onUndo = viewModel::undo,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            if (rooms.isEmpty() && measurements.isEmpty() && project != null) {
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

private fun summarise(
    rooms: List<SavedRoom>,
    measurements: List<com.measure.core.data.SavedMeasurement>,
    viewModel: EditorViewModel,
): String {
    val parts = buildList {
        if (rooms.isNotEmpty()) {
            add("${rooms.size} ${if (rooms.size == 1) "room" else "rooms"}")
            add(
                com.measure.core.units.AreaFormatter.format(
                    com.measure.core.units.Area(rooms.sumOf { it.area.squareMetres }),
                    viewModel.unitSystem(),
                ),
            )
        }
        if (measurements.isNotEmpty()) {
            add("${measurements.size} ${if (measurements.size == 1) "measurement" else "measurements"}")
        }
    }
    return if (parts.isEmpty()) "Nothing yet" else parts.joinToString(" · ")
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onAddRoom: () -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Undo", enabled = canUndo, onClick = onUndo)
            Pill("Add", onClick = onAddRoom)
        }
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
    val project by viewModel.project.collectAsStateWithLifecycle()
    val measurements = project?.measurements.orEmpty()

    Column(
        modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(16.dp))
            // Opaque, not the camera scrim. There is no camera behind this screen, and a
            // translucent panel let the plan's own lines and labels bleed through the
            // text, which read as a rendering fault.
            .background(MeasureColours.Panel)
            // Capped and scrollable. Adding four doors made the panel taller than the
            // screen, so the rows confirming each one were off the bottom — which is why
            // the same door got added again and again.
            .heightIn(max = PANEL_MAX_HEIGHT)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (selection) {
            Selection.None -> {
                MeasurementList(viewModel, measurements)
                Text(
                    text = "Pinch to zoom · tap a wall to set its true length · " +
                        "long-press a corner to move it",
                    color = MeasureColours.OnScrimMuted,
                    fontSize = 13.sp,
                )
            }

            is Selection.Corner -> CornerPanel(viewModel, selection)
            is Selection.Wall -> WallPanel(viewModel, selection)
            is Selection.Measurement -> MeasurementPanel(viewModel, selection)
            is Selection.Room -> RoomPanel(viewModel, selection)
        }
    }
}

/**
 * The room itself: its height, and what that height makes calculable.
 *
 * Wall area and volume appear only once a height is known, and nothing substitutes a
 * typical 2.4 m in the meantime. A guessed paint estimate is indistinguishable from a
 * measured one on screen, and the user has no way to tell which they are looking at.
 */
@Composable
private fun RoomPanel(viewModel: EditorViewModel, selection: Selection.Room) {
    val room = viewModel.roomById(selection.roomId) ?: return
    val surfaces = room.surfaces

    var name by remember(room.id, room.name) { mutableStateOf(room.name) }
    var height by remember(room.id, room.ceilingHeight) {
        mutableStateOf(room.ceilingHeight?.let { viewModel.formatLength(it) } ?: "")
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Field(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.weight(1f),
        )
        Pill("Rename", onClick = { viewModel.renameRoom(room.id, name) })
    }

    Text(
        text = "${viewModel.formatArea(room)} floor · ${viewModel.formatLength(room.perimeter.metres)} perimeter",
        color = MeasureColours.OnScrimMuted,
        fontSize = 12.sp,
    )

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Ceiling", color = MeasureColours.OnScrimMuted, fontSize = 13.sp)
        Field(
            value = height,
            onValueChange = { height = it },
            numeric = true,
            modifier = Modifier.weight(1f),
        )
        Pill("Set", onClick = { viewModel.setCeilingHeight(room.id, height) })
    }

    if (surfaces == null) {
        Text(
            text = "Set a ceiling height for wall area and volume. Look up while capturing " +
                "and it fills itself in.",
            color = MeasureColours.OnScrimMuted,
            fontSize = 12.sp,
        )
    } else {
        Text(
            text = "Walls ${com.measure.core.units.AreaFormatter.format(surfaces.netWallArea, viewModel.unitSystem())}" +
                " · volume ${com.measure.core.units.VolumeFormatter.format(surfaces.volume, viewModel.unitSystem())}",
            color = MeasureColours.OnScrim,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (surfaces.openingArea.squareMetres > 0.0) {
            Text(
                text = "After taking out " +
                    com.measure.core.units.AreaFormatter.format(surfaces.openingArea, viewModel.unitSystem()) +
                    " of doors and windows",
                color = MeasureColours.OnScrimMuted,
                fontSize = 12.sp,
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Delete room", onClick = { viewModel.deleteRoom(room.id) })
        Pill("Done", onClick = viewModel::clearSelection)
    }
}

/**
 * Every standalone measurement, as a list.
 *
 * A plan is the wrong shape for these and no amount of drawing fixes it: a room height is
 * vertical, and a floor plan has no vertical. Two heights measured one after the other
 * projected onto the floor as two dots a few centimetres apart, which told the user
 * nothing they had measured. The values are the content, so the values are what is shown,
 * and each row selects its measurement on the plan so the two views agree.
 */
@Composable
private fun MeasurementList(
    viewModel: EditorViewModel,
    measurements: List<com.measure.core.data.SavedMeasurement>,
) {
    if (measurements.isEmpty()) return

    Text(
        text = "${measurements.size} ${if (measurements.size == 1) "measurement" else "measurements"}",
        color = MeasureColours.OnScrim,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
    )

    measurements.forEach { measurement ->
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MeasureColours.ScrimSoft)
                .clickable { viewModel.select(Selection.Measurement(measurement.id)) }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = com.measure.core.units.LengthFormatter.formatWithUncertainty(
                    measurement.length,
                    measurement.sigma,
                    viewModel.unitSystem(),
                ),
                color = MeasureColours.OnScrim,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = measurement.mode.label,
                color = MeasureColours.OnScrimMuted,
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * A standalone distance, with its tolerance.
 *
 * These are drawn and selectable on the plan rather than only counted, because a saved
 * measurement the app will not show you is a measurement you have to take again.
 */
@Composable
private fun MeasurementPanel(viewModel: EditorViewModel, selection: Selection.Measurement) {
    val measurement = viewModel.measurementById(selection.id) ?: return

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = com.measure.core.units.LengthFormatter.formatWithUncertainty(
                    measurement.length,
                    measurement.sigma,
                    viewModel.unitSystem(),
                ),
                color = MeasureColours.OnScrim,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = measurement.mode.label + " measurement",
                color = MeasureColours.OnScrimMuted,
                fontSize = 12.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Delete", onClick = { viewModel.deleteMeasurement(measurement.id) })
            Pill("Done", onClick = viewModel::clearSelection)
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
        Field(
            value = typed,
            onValueChange = { typed = it },
            numeric = true,
            modifier = Modifier.weight(1f),
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

    OpeningsSection(viewModel, room, selection.index)
}

private fun describeOpenings(openings: List<com.measure.core.data.SavedOpening>): String {
    if (openings.isEmpty()) return "No doors or windows"
    val doors = openings.count { it.opening.kind == OpeningKind.DOOR }
    val windows = openings.count { it.opening.kind == OpeningKind.WINDOW }
    val other = openings.size - doors - windows
    return buildList {
        if (doors > 0) add("$doors ${if (doors == 1) "door" else "doors"}")
        if (windows > 0) add("$windows ${if (windows == 1) "window" else "windows"}")
        if (other > 0) add("$other ${if (other == 1) "opening" else "openings"}")
    }.joinToString(", ")
}

/**
 * Doors and windows in the selected wall.
 *
 * Added at a standard size and adjusted, rather than typed from scratch: a standard
 * internal door really is about 830 x 2040 mm, and making someone enter four numbers per
 * doorway is what stops openings being recorded at all — at which point the paint
 * estimate silently includes the door.
 */
@Composable
private fun OpeningsSection(
    viewModel: EditorViewModel,
    room: com.measure.core.data.SavedRoom,
    wallIndex: Int,
) {
    val openings = room.openings[wallIndex].orEmpty()

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = describeOpenings(openings),
            color = if (openings.isEmpty()) MeasureColours.OnScrimMuted else MeasureColours.Ready,
            fontSize = 12.sp,
            fontWeight = if (openings.isEmpty()) FontWeight.Normal else FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("+ Door", onClick = { viewModel.addOpening(room.id, wallIndex, OpeningKind.DOOR) })
            Pill("+ Window", onClick = { viewModel.addOpening(room.id, wallIndex, OpeningKind.WINDOW) })
        }
    }

    openings.forEachIndexed { position, saved ->
        OpeningRow(viewModel, room, saved, position + 1)
    }
}

@Composable
private fun OpeningRow(
    viewModel: EditorViewModel,
    room: com.measure.core.data.SavedRoom,
    saved: com.measure.core.data.SavedOpening,
    position: Int,
) {
    var width by remember(saved.id, saved.opening.width) {
        mutableStateOf(viewModel.formatLength(saved.opening.width))
    }
    var height by remember(saved.id, saved.opening.height) {
        mutableStateOf(viewModel.formatLength(saved.opening.height))
    }
    var offset by remember(saved.id, saved.opening.offset) {
        mutableStateOf(viewModel.formatLength(saved.opening.offset))
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Numbered and positioned, because four identical "Door 830 x 2040" rows are
        // indistinguishable and there is no way to tell which one is the one you meant.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${saved.opening.kind.label} $position",
                color = MeasureColours.OnScrim,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Pill("Remove", onClick = { viewModel.deleteOpening(saved.id) })
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Field(value = width, onValueChange = { width = it }, numeric = true, modifier = Modifier.weight(1f))
            Text("×", color = MeasureColours.OnScrimMuted, fontSize = 13.sp)
            Field(value = height, onValueChange = { height = it }, numeric = true, modifier = Modifier.weight(1f))
            Text("at", color = MeasureColours.OnScrimMuted, fontSize = 13.sp)
            Field(value = offset, onValueChange = { offset = it }, numeric = true, modifier = Modifier.weight(1f))
            Pill(
                label = "Set",
                onClick = {
                    viewModel.resizeOpening(
                        roomId = room.id,
                        saved = saved,
                        width = viewModel.parseLength(width),
                        height = viewModel.parseLength(height),
                        offset = viewModel.parseLength(offset),
                        sill = null,
                    )
                },
            )
        }
    }
}

/** A text field styled like the rest of the panel. */
@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    numeric: Boolean = false,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = MeasureColours.OnScrim, fontSize = 15.sp),
        cursorBrush = SolidColor(MeasureColours.Ready),
        keyboardOptions = if (numeric) {
            KeyboardOptions(keyboardType = KeyboardType.Decimal)
        } else {
            KeyboardOptions.Default
        },
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MeasureColours.ScrimSoft)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    )
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

/** Enough for a wall with a few openings, little enough to leave the plan visible. */
private val PANEL_MAX_HEIGHT = 340.dp
