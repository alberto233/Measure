package com.measure.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.measure.core.data.SavedRoom
import com.measure.core.designsystem.MeasureButton
import com.measure.core.designsystem.MeasureColours
import com.measure.core.designsystem.MeasureField
import com.measure.core.designsystem.touchTarget
import com.measure.core.geometry.OpeningKind
import com.measure.feature.export.ExportSheet
import com.measure.feature.export.PlanExporter
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

    // The view model's Compose mirror rather than the flow, so this screen and every
    // panel under it read the model the same way — see EditorViewModel.current.
    val project = viewModel.current
    val rooms = project?.rooms.orEmpty()
    val measurements = project?.measurements.orEmpty()

    val context = LocalContext.current
    var exporting by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(MeasureColours.Surface)) {
        PlanCanvas(
            rooms = rooms,
            measurements = measurements,
            planMeasurements = project?.planMeasurements.orEmpty(),
            selection = viewModel.selection,
            dragging = viewModel.dragging,
            movingRoom = viewModel.movingRoom,
            measuring = viewModel.mode == EditorMode.MEASURE,
            drawing = viewModel.drawing,
            focus = viewModel.focus,
            pendingEnd = viewModel.pendingEnd,
            dimensionChains = if (viewModel.mode == EditorMode.MEASURE) {
                viewModel.dimensionChains()
            } else {
                emptyList()
            },
            formatLength = viewModel::formatLength,
            onSelect = viewModel::select,
            onFocus = viewModel::focusOn,
            onMeasureTap = viewModel::tapWhileMeasuring,
            onBeginDrag = viewModel::beginDrag,
            onDrag = viewModel::updateDrag,
            onEndDrag = viewModel::endDrag,
            onBeginRoomMove = viewModel::beginRoomMove,
            onRoomMove = viewModel::updateRoomMove,
            onEndRoomMove = viewModel::endRoomMove,
            modifier = Modifier.fillMaxSize(),
        )

        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.align(Alignment.TopCenter)) {
                TopBar(
                    title = project?.name ?: "",
                    subtitle = summarise(rooms, measurements, viewModel),
                    onBack = onBack,
                    onAddRoom = onAddRoom,
                    canUndo = viewModel.canUndo,
                    onUndo = viewModel::undo,
                    measuring = viewModel.mode == EditorMode.MEASURE,
                    onToggleMeasure = {
                        viewModel.selectMode(
                            if (viewModel.mode == EditorMode.MEASURE) EditorMode.SELECT else EditorMode.MEASURE,
                        )
                    },
                    // Nothing to send until something has been captured, and a share
                    // sheet offering an empty drawing is worse than no button.
                    canExport = rooms.isNotEmpty() || measurements.isNotEmpty(),
                    onExport = { exporting = true },
                )
                if (viewModel.mode == EditorMode.MEASURE) {
                    MeasuringBanner(viewModel)
                } else if (project?.hasUnrelatedCaptures == true) {
                    UnrelatedCapturesNote()
                }
            }

            if (rooms.isEmpty() && measurements.isEmpty() && project != null) {
                EmptyPlan(Modifier.align(Alignment.Center))
            }

            // The panel is the only thing that must clear the keyboard: the plan behind it
            // should stay where it is rather than being squashed into a letterbox.
            val panelModifier = Modifier.align(Alignment.BottomCenter).imePadding()
            when {
                exporting -> ExportSheet(
                    onExport = { format ->
                        exporting = false
                        val detail = project ?: return@ExportSheet
                        // Failures are surfaced rather than swallowed: a share sheet that
                        // does not appear is indistinguishable from a tap that missed.
                        runCatching {
                            context.startActivity(
                                android.content.Intent.createChooser(
                                    PlanExporter.share(context, detail, format),
                                    "Send ${detail.name}",
                                ),
                            )
                        }.onFailure { viewModel.reportExportFailure(it) }
                    },
                    onDismiss = { exporting = false },
                    modifier = panelModifier,
                )

                viewModel.mode == EditorMode.MEASURE -> MeasurePanel(viewModel, panelModifier)
                else -> SelectionPanel(viewModel, panelModifier)
            }

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
                    // Amber for a refusal, teal for something that worked. Confirming a
                    // success in the colour used for problems teaches people to read
                    // every message as a problem, and then to stop reading them.
                    color = if (viewModel.messageIsWarning) {
                        MeasureColours.Warning
                    } else {
                        MeasureColours.Ready
                    },
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
    measuring: Boolean,
    onToggleMeasure: () -> Unit,
    canExport: Boolean,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill("Back", onClick = onBack)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = MeasureColours.OnScrim, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MeasureColours.OnScrimMuted, fontSize = 12.sp)
            }
            Pill("Add", onClick = onAddRoom)
        }
        // On its own row rather than crowded into the first. Five pills across a phone
        // leaves each one too narrow to read, which is how the capture screen's buttons
        // ended up unreadable at the top edge.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Lit while active. A mode that changes what a tap does has to be visible
            // from the control that turned it on, or the plan simply stops behaving.
            Pill("Measure", highlighted = measuring, onClick = onToggleMeasure)
            Pill("Send", enabled = canExport, onClick = onExport)
            Pill("Undo", enabled = canUndo, onClick = onUndo)
        }
    }
}

/**
 * Says that the rooms' arrangement was never measured.
 *
 * Shown whenever a plan holds rooms from more than one AR session. Each session gave the
 * phone a fresh origin, so the app has no measurement of how one room sits relative to
 * another and simply set the later ones down alongside the earlier ones.
 *
 * Stated rather than left to be discovered, because a floor plan looks equally authoritative
 * either way. Every number *inside* each room is as good as it ever was; the distance
 * between two of them is a layout the user is free to arrange, and they can only know which
 * is which if the screen tells them.
 */
@Composable
private fun UnrelatedCapturesNote() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MeasureColours.Panel)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = "Rooms from separate captures",
            color = MeasureColours.Warning,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Each room is measured, but how they sit together is not — " +
                "long-press a room and drag to place it.",
            color = MeasureColours.OnScrimMuted,
            fontSize = 12.sp,
        )
    }
}

/**
 * What the measure view is doing, and the one control that changes it.
 *
 * Reading and drawing are separate states with separate affordances, so a tap on the plan
 * never has to be guessed at: while reading it selects something to read, while drawing it
 * places a point, and the banner says which. The alternative — one tap meaning two things
 * depending on invisible state — is the shape of every interaction fault this app has hit.
 */
@Composable
private fun MeasuringBanner(viewModel: EditorViewModel) {
    val pending = viewModel.pendingEnd
    val drawing = viewModel.drawing

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MeasureColours.Panel)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = when {
                    drawing && pending == null -> "Tap the first point"
                    drawing -> "Tap the second point"
                    else -> "Tap a dimension to read it"
                },
                color = MeasureColours.OnScrim,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when {
                    pending != null -> "From ${pending.description}"
                    drawing -> "Corners and walls pull the point onto them"
                    else -> "Sizes are marked around the plan"
                },
                color = if (pending == null) MeasureColours.OnScrimMuted else MeasureColours.Accent,
                fontSize = 12.sp,
            )
            viewModel.lastStraightening?.let { straightened ->
                Text("Pulled $straightened", color = MeasureColours.Accent, fontSize = 11.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                pending != null -> {
                    Pill("Redo point", onClick = viewModel::clearPendingEnd)
                    Pill("Cancel", onClick = viewModel::cancelDrawing)
                }

                drawing -> Pill("Cancel", onClick = viewModel::cancelDrawing)

                // Refused rather than hidden while a measurement is unconfirmed, so the
                // reason is visible instead of the control merely being absent.
                else -> Pill(
                    label = "+ Distance",
                    enabled = viewModel.unconfirmed == null,
                    highlighted = true,
                    onClick = viewModel::beginDrawing,
                )
            }
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
    val measurements = viewModel.current?.measurements.orEmpty()

    val scroll = rememberScrollState()

    // Scroll the row confirming a new opening into view.
    //
    // The panel is capped and scrolls, so a door added to a wall that already had two
    // landed below the fold — and the note on `heightIn` below records what that did last
    // time: the user could not see the confirmation, assumed the button had missed, and
    // added the same door four times. Making the panel update was only half the fix; the
    // update has to be somewhere it can be seen.
    LaunchedEffect(viewModel.lastAddedOpening) {
        if (viewModel.lastAddedOpening == null) return@LaunchedEffect
        // One frame, so the new row has been measured and `maxValue` includes it.
        withFrameNanos { }
        scroll.animateScrollTo(scroll.maxValue)
    }

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
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (selection) {
            Selection.None -> {
                MeasurementList(viewModel, measurements)
                Text(
                    text = "Pinch to zoom · tap a wall to set its true length · " +
                        "long-press a corner to move it · Measure for sizes and distances",
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
            hint = "Room name",
            modifier = Modifier.weight(1f),
        )
        Pill("Rename", onClick = { viewModel.renameRoom(room.id, name) })
    }

    Text(
        text = "${viewModel.formatArea(room)} floor · ${viewModel.formatLength(room.perimeter.metres)} perimeter",
        color = MeasureColours.OnScrimMuted,
        fontSize = 12.sp,
    )

    // Placing a room takes both of these. Moving alone leaves a plan whose pieces slide
    // but never turn, and a room arrives at whatever angle the phone was facing when its
    // capture began — so the turn controls are not a refinement of the move, they are the
    // other half of it.
    Text(
        text = "Long-press this room and drag to move it",
        color = MeasureColours.OnScrimMuted,
        fontSize = 12.sp,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Square to plan", onClick = { viewModel.squareRoomToPlan(room.id) })
        Pill("⟲ 90°", onClick = { viewModel.turnRoom(room.id, 90.0) })
        Pill("⟳ 90°", onClick = { viewModel.turnRoom(room.id, -90.0) })
    }

    // The bounding box, because "will it fit" is asked about a rectangle far more often
    // than about a floor area. Deliberately not a "largest clear span", which has no
    // agreed meaning for a room that is not convex and would be a number nobody could
    // check.
    viewModel.boundingSize(room)?.let { (width, depth) ->
        Text(
            text = "Fits inside ${viewModel.formatLength(width)} × ${viewModel.formatLength(depth)}",
            color = MeasureColours.OnScrimMuted,
            fontSize = 12.sp,
        )
    }

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
            hint = "e.g. 2.4",
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
                .touchTarget()
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
 * The measure view's panel: whatever one thing is being read, or nothing.
 *
 * Deliberately not the editing panel. This view exists to answer a question, and mixing
 * in controls that reshape the room would invite an edit while the user is reading — the
 * two are different jobs and the screen says which one it is doing.
 */
@Composable
private fun MeasurePanel(viewModel: EditorViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MeasureColours.Panel)
            .heightIn(max = PANEL_MAX_HEIGHT)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (val focus = viewModel.focus) {
            MeasureFocus.None -> Text(
                text = "Tap any dimension line for its size · " +
                    "+ Distance measures between two points you choose",
                color = MeasureColours.OnScrimMuted,
                fontSize = 13.sp,
            )

            is MeasureFocus.Dimension -> DimensionReadout(viewModel, focus)
            is MeasureFocus.Custom -> CustomDistanceReadout(viewModel, focus)
        }
    }
}

@Composable
private fun DimensionReadout(viewModel: EditorViewModel, focus: MeasureFocus.Dimension) {
    val length = viewModel.focusedDimensionLength() ?: return

    Text(
        text = viewModel.formatLength(length),
        color = MeasureColours.OnScrim,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = if (focus.isOverall) "Overall, across the whole plan" else "Between the marked corners",
        color = MeasureColours.OnScrimMuted,
        fontSize = 12.sp,
    )
    Text(
        text = "The dashed lines show which part of the plan this covers.",
        color = MeasureColours.OnScrimMuted,
        fontSize = 12.sp,
    )
}

/**
 * A distance the user drew, and — while it is new — whether they want to keep it.
 *
 * Nothing else can be drawn until this is answered. Letting a second measurement start
 * the instant the first lands is how a plan quietly fills with lines nobody meant to
 * keep, which is the same fault as the door that got added seven times.
 */
@Composable
private fun CustomDistanceReadout(viewModel: EditorViewModel, focus: MeasureFocus.Custom) {
    val saved = viewModel.planMeasurementById(focus.id) ?: return
    val measurement = saved.measurement
    val unconfirmed = viewModel.unconfirmed == focus.id

    if (measurement == null) {
        Text(
            text = "This distance was attached to geometry that has gone",
            color = MeasureColours.Warning,
            fontSize = 14.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Delete", onClick = { viewModel.deletePlanMeasurement(saved.id) })
        }
        return
    }

    Text(
        text = com.measure.core.units.LengthFormatter.formatWithUncertainty(
            com.measure.core.units.Length(measurement.length),
            com.measure.core.units.Length(measurement.sigma),
            viewModel.unitSystem(),
        ),
        color = MeasureColours.OnScrim,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = "Off the plan, not measured in the room",
        color = MeasureColours.Warning,
        fontSize = 12.sp,
    )
    Text(
        text = "${measurement.from.description} → ${measurement.to.description}",
        color = MeasureColours.OnScrimMuted,
        fontSize = 12.sp,
    )
    if (measurement.isModelled) {
        Text(
            text = "One end sits on a corner the solver squared up, so part of this " +
                "distance is the model rather than the room.",
            color = MeasureColours.OnScrimMuted,
            fontSize = 12.sp,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (unconfirmed) {
            Pill("Keep", highlighted = true, onClick = viewModel::keepMeasurement)
            Pill("Discard", onClick = viewModel::discardMeasurement)
        } else {
            Pill("Delete", onClick = { viewModel.deletePlanMeasurement(saved.id) })
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
            hint = "True length",
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
            color = if (openings.isEmpty()) MeasureColours.OnScrimMuted else MeasureColours.OnScrim,
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
            Field(width, { width = it }, numeric = true, hint = "wide", modifier = Modifier.weight(1f))
            Text("×", color = MeasureColours.OnScrimMuted, fontSize = 13.sp)
            Field(height, { height = it }, numeric = true, hint = "high", modifier = Modifier.weight(1f))
            Text("at", color = MeasureColours.OnScrimMuted, fontSize = 13.sp)
            Field(offset, { offset = it }, numeric = true, hint = "from", modifier = Modifier.weight(1f))
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

/**
 * A text field that looks like one.
 *
 * It used to be a rounded rectangle a shade off the panel behind it, which on a dark
 * panel is no distinction at all: an empty field was indistinguishable from a gap, and
 * the only sign the app wanted a number was a blinking cursor. A visible edge and a
 * greyed hint say what the box is for before it is tapped, and the edge lights up when
 * it has focus so it is obvious which of four boxes the keyboard is typing into.
 */
/**
 * The editor's field and button, which are now the shared ones.
 *
 * These were the last two duplicates. `Field` and `Pill` existed here because the versions
 * in the projects module were in another module, and between them they were most of the
 * reason restyling this app meant editing 87 call sites. They stay as named functions so
 * that the several dozen uses below did not all have to change in the same commit, and
 * both are now one line.
 */
@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    numeric: Boolean = false,
    hint: String = "",
    modifier: Modifier = Modifier,
) = MeasureField(value, onValueChange, modifier, hint, numeric)

@Composable
private fun Pill(
    label: String,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = MeasureButton(label, onClick, modifier, primary = highlighted, enabled = enabled)

private const val MESSAGE_DURATION_MS = 3000L

/** Enough for a wall with a few openings, little enough to leave the plan visible. */
private val PANEL_MAX_HEIGHT = 340.dp
