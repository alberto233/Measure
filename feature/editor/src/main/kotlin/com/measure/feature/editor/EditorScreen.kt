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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.measure.core.data.SavedRoom
import com.measure.core.designsystem.MeasureButton
import com.measure.core.designsystem.MeasureCard
import com.measure.core.designsystem.MeasureChip
import com.measure.core.designsystem.MeasureColours
import com.measure.core.designsystem.MeasureField
import com.measure.core.designsystem.MeasureReading
import com.measure.core.designsystem.MeasureRule
import com.measure.core.designsystem.MeasureSegmented
import com.measure.core.designsystem.MeasureShape
import com.measure.core.designsystem.MeasureSheet
import com.measure.core.designsystem.MeasureSheetDefaults
import com.measure.core.designsystem.MeasureSpace
import com.measure.core.designsystem.MeasureTag
import com.measure.core.designsystem.MeasureType
import com.measure.core.designsystem.touchTarget
import com.measure.core.geometry.Flooring
import com.measure.core.geometry.OpeningKind
import com.measure.core.geometry.Painting
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

    val mode = viewModel.mode
    // Only the plan view reshapes anything. In the measure view PlanCanvas already refuses
    // drags; the quantities view is new and would otherwise let a room be dragged across
    // the floor while the user was reading how much paint to buy.
    val editable = mode == EditorMode.PLAN

    var sheetExpanded by remember { mutableStateOf(false) }
    val sheetScroll = rememberScrollState()

    // What the chrome covers, so the opening fit puts the plan in the part of the canvas
    // that can actually be seen. The canvas is full-screen and the bars are drawn over it,
    // so fitting to the canvas fits to a rectangle a third of which is hidden — on a
    // two-room plan the second room opened underneath the sheet.
    //
    // The top is measured, because it genuinely varies: a banner appears and disappears.
    // The bottom is the sheet's *peek* height rather than its measured height, which is not
    // laziness — the sheet expands whenever something is selected, and a fit that happens
    // mid-animation reads a height the sheet is only passing through. That squashed the
    // plan into a sliver at the top of the screen.
    var topChromePx by remember { mutableIntStateOf(0) }
    val bottomChromePx = with(LocalDensity.current) { MeasureSheetDefaults.PeekHeight.roundToPx() }

    // Anything selected has a panel with controls in it, and a keyboard over a peeked sheet
    // covers the field it opened for. The quantities view is a reading rather than a
    // control, and it is all of it worth seeing, so it opens expanded.
    LaunchedEffect(viewModel.selection) {
        if (viewModel.selection != Selection.None) sheetExpanded = true
    }
    LaunchedEffect(mode) { sheetExpanded = mode == EditorMode.QUANTITIES }
    // Tapping a dimension in the measure view fills the sheet with a readout and three
    // lines explaining it, and at peek height the last line was cut off mid-sentence —
    // "part of this distance is" and then nothing. The answer arriving is exactly when
    // the sheet needs to be big enough to hold it.
    LaunchedEffect(viewModel.focus) {
        if (viewModel.focus != MeasureFocus.None) sheetExpanded = true
    }

    Box(modifier.fillMaxSize().background(MeasureColours.Surface)) {
        PlanCanvas(
            rooms = rooms,
            measurements = measurements,
            planMeasurements = project?.planMeasurements.orEmpty(),
            selection = viewModel.selection,
            dragging = viewModel.dragging,
            movingRoom = viewModel.movingRoom,
            topInsetPx = topChromePx,
            bottomInsetPx = bottomChromePx,
            measuring = mode == EditorMode.MEASURE,
            drawing = viewModel.drawing,
            focus = viewModel.focus,
            pendingEnd = viewModel.pendingEnd,
            dimensionChains = if (mode == EditorMode.MEASURE) {
                viewModel.dimensionChains()
            } else {
                emptyList()
            },
            formatLength = viewModel::formatLength,
            onSelect = { picked ->
                // In the quantities view the plan is a reference rather than a workbench:
                // a tap picks out a whole room, so the breakdown below can mark which line
                // belongs to the shape under the finger. Selecting a single wall there
                // would offer an edit the view has no controls for.
                if (mode == EditorMode.QUANTITIES) {
                    roomIdOf(picked)?.let { viewModel.select(Selection.Room(it)) }
                } else {
                    viewModel.select(picked)
                }
            },
            onFocus = viewModel::focusOn,
            onMeasureTap = viewModel::tapWhileMeasuring,
            onBeginDrag = { roomId, index, at -> if (editable) viewModel.beginDrag(roomId, index, at) },
            onDrag = { at -> if (editable) viewModel.updateDrag(at) },
            onEndDrag = { if (editable) viewModel.endDrag() },
            onBeginRoomMove = { roomId, at -> if (editable) viewModel.beginRoomMove(roomId, at) },
            onRoomMove = { at -> if (editable) viewModel.updateRoomMove(at) },
            onEndRoomMove = { if (editable) viewModel.endRoomMove() },
            modifier = Modifier.fillMaxSize(),
        )

        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .onGloballyPositioned { topChromePx = it.boundsInRoot().bottom.toInt() },
            ) {
                TopBar(
                    title = project?.name ?: "",
                    subtitle = summarise(rooms, measurements, viewModel),
                    onBack = onBack,
                    canUndo = viewModel.canUndo,
                    onUndo = viewModel::undo,
                    mode = mode,
                    onSelectMode = viewModel::selectMode,
                    // Nothing to send until something has been captured, and a share
                    // sheet offering an empty drawing is worse than no button.
                    canExport = rooms.isNotEmpty() || measurements.isNotEmpty(),
                    onExport = { exporting = true },
                )
                if (mode == EditorMode.MEASURE) {
                    MeasuringBanner(viewModel)
                } else if (mode == EditorMode.PLAN && project?.hasUnrelatedCaptures == true) {
                    UnrelatedCapturesNote()
                }
            }

            if (rooms.isEmpty() && measurements.isEmpty() && project != null) {
                EmptyPlan(onAddRoom, Modifier.align(Alignment.Center))
            }

            // The sheet is the only thing that must clear the keyboard: the plan behind it
            // should stay where it is rather than being squashed into a letterbox.
            val panelModifier = Modifier.align(Alignment.BottomCenter).imePadding()
            if (exporting) {
                ExportSheet(
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
            } else {
                MeasureSheet(
                    expanded = sheetExpanded,
                    onExpandedChange = { sheetExpanded = it },
                    modifier = panelModifier,
                    scrollState = sheetScroll,
                ) {
                    when (mode) {
                        EditorMode.PLAN -> PlanContent(viewModel, onAddRoom, sheetScroll) {
                            sheetExpanded = true
                        }

                        EditorMode.MEASURE -> MeasureContent(viewModel)
                        EditorMode.QUANTITIES -> QuantitiesContent(viewModel)
                    }
                }
            }

            viewModel.message?.let { text ->
                LaunchedEffect(text) {
                    delay(MESSAGE_DURATION_MS)
                    viewModel.dismissMessage()
                }
                // A light card, not the dark slab it was. That slab was correct while the
                // whole app was dark and is now the only black thing on a white screen,
                // which reads as a leftover rather than as a message.
                Text(
                    text = text,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(MeasureShape.Panel))
                        .background(MeasureColours.Panel)
                        .border(
                            1.dp,
                            if (viewModel.messageIsWarning) {
                                MeasureColours.Warning
                            } else {
                                MeasureColours.Ready
                            },
                            RoundedCornerShape(MeasureShape.Panel),
                        )
                        .padding(horizontal = MeasureSpace.Base, vertical = MeasureSpace.Snug),
                    // Amber for a refusal, teal for something that worked. Confirming a
                    // success in the colour used for problems teaches people to read
                    // every message as a problem, and then to stop reading them.
                    color = if (viewModel.messageIsWarning) {
                        MeasureColours.Warning
                    } else {
                        MeasureColours.Ready
                    },
                    fontSize = MeasureType.Label.fontSize,
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

/** Which room a tap landed in, whatever part of it was hit. */
private fun roomIdOf(selection: Selection): Long? = when (selection) {
    is Selection.Room -> selection.roomId
    is Selection.Wall -> selection.roomId
    is Selection.Corner -> selection.roomId
    else -> null
}

/**
 * The plan's identity, its two actions, and the mode switch.
 *
 * One row of controls rather than two. The second row existed because Measure, Send and
 * Undo had nowhere else to go — five buttons across a phone leaves each too narrow to read,
 * which is how the capture screen's controls once became unreadable. The segmented control
 * takes Measure out of the row and gives the other two the space, and Add moved into the
 * plan view's own panel where it belongs: adding a room is something you do to a plan, not
 * something you do to the editor.
 */
@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    mode: EditorMode,
    onSelectMode: (EditorMode) -> Unit,
    canExport: Boolean,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(MeasureSpace.Base),
        verticalArrangement = Arrangement.spacedBy(MeasureSpace.Snug),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MeasureSpace.Tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill("←", onClick = onBack)
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MeasureColours.Ink,
                    style = MeasureType.Title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = MeasureColours.InkMuted,
                    style = MeasureType.Small,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Disabled rather than absent. A control that comes and goes makes the row
            // reflow under the finger, and "where did Undo go" is a worse question than
            // "why is Undo grey".
            Pill("Undo", enabled = canUndo, onClick = onUndo)
            Pill("Send", enabled = canExport, onClick = onExport)
        }
        MeasureSegmented(
            options = EditorMode.entries.map { it.label },
            selectedIndex = mode.ordinal,
            onSelect = { onSelectMode(EditorMode.entries[it]) },
        )
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
    MeasureCard(Modifier.fillMaxWidth().padding(horizontal = MeasureSpace.Base)) {
        Text(
            text = "Rooms from separate captures",
            color = MeasureColours.Warning,
            fontSize = MeasureType.Label.fontSize,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Each room is measured, but how they sit together is not — " +
                "long-press a room and drag to place it.",
            color = MeasureColours.InkMuted,
            fontSize = MeasureType.Small.fontSize,
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

    MeasureCard(Modifier.fillMaxWidth().padding(horizontal = MeasureSpace.Base)) {
      Row(
        Modifier.fillMaxWidth(),
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
                color = MeasureColours.Ink,
                fontSize = MeasureType.Label.fontSize,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when {
                    pending != null -> "From ${pending.description}"
                    drawing -> "Corners and walls pull the point onto them"
                    else -> "Sizes are marked around the plan"
                },
                color = if (pending == null) MeasureColours.InkMuted else MeasureColours.Accent,
                fontSize = MeasureType.Small.fontSize,
            )
            viewModel.lastStraightening?.let { straightened ->
                Text("Pulled $straightened", color = MeasureColours.Accent, fontSize = MeasureType.Small.fontSize)
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
}

@Composable
private fun EmptyPlan(onAddRoom: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MeasureSpace.Snug),
    ) {
        Text("Nothing to edit yet", color = MeasureColours.Ink, style = MeasureType.Title)
        Text(
            "Capture a room and it appears here",
            color = MeasureColours.InkMuted,
            style = MeasureType.Label,
        )
        // The way out of the empty state, on the empty state. An empty screen whose only
        // route forward is a control somewhere else is a dead end for as long as it takes
        // to find that control.
        Pill("+ Room", highlighted = true, onClick = onAddRoom)
    }
}

/**
 * What can be done with whatever is selected.
 *
 * Nothing selected shows the gesture hint instead. A plan with no visible affordances is
 * a plan people assume is read-only, and long-press-to-drag is not discoverable.
 */
@Composable
private fun PlanContent(
    viewModel: EditorViewModel,
    onAddRoom: () -> Unit,
    scroll: ScrollState,
    onNeedRoom: () -> Unit,
) {
    val selection = viewModel.selection
    val measurements = viewModel.current?.measurements.orEmpty()

    // Bring the row confirming a new opening into view, and open the sheet far enough that
    // there is a view to bring it into.
    //
    // What happened without this: a door added to a wall that already had two landed below
    // the fold, the user could not see the confirmation, assumed the button had missed, and
    // added the same door four times. Making the panel update was only the first half of
    // the fix, scrolling to the new row was the second, and the sheet — which can now be
    // made taller rather than being capped at 340 dp — is the third.
    LaunchedEffect(viewModel.lastAddedOpening) {
        if (viewModel.lastAddedOpening == null) return@LaunchedEffect
        onNeedRoom()
        // One frame, so the new row has been measured and `maxValue` includes it.
        withFrameNanos { }
        scroll.animateScrollTo(scroll.maxValue)
    }

    when (selection) {
        Selection.None -> {
            // Adding a room is the plan view's own action, which is why it is here rather
            // than in the top bar: it belongs to the plan, not to the editor's chrome.
            Pill("+ Room", highlighted = true, onClick = onAddRoom)
            MeasurementList(viewModel, measurements)
            Text(
                text = "Pinch to zoom · tap a wall to set its true length · " +
                    "long-press a corner to move it · Measure for sizes and distances",
                color = MeasureColours.InkMuted,
                style = MeasureType.Label,
            )
        }

        is Selection.Corner -> CornerPanel(viewModel, selection)
        is Selection.Wall -> WallPanel(viewModel, selection)
        is Selection.Measurement -> MeasurementPanel(viewModel, selection)
        is Selection.Room -> RoomPanel(viewModel, selection)
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
        color = MeasureColours.InkMuted,
        fontSize = MeasureType.Small.fontSize,
    )

    // Placing a room takes both of these. Moving alone leaves a plan whose pieces slide
    // but never turn, and a room arrives at whatever angle the phone was facing when its
    // capture began — so the turn controls are not a refinement of the move, they are the
    // other half of it.
    Text(
        text = "Long-press this room and drag to move it",
        color = MeasureColours.InkMuted,
        fontSize = MeasureType.Small.fontSize,
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
            color = MeasureColours.InkMuted,
            fontSize = MeasureType.Small.fontSize,
        )
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Ceiling", color = MeasureColours.InkMuted, fontSize = MeasureType.Label.fontSize)
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
            color = MeasureColours.InkMuted,
            fontSize = MeasureType.Small.fontSize,
        )
    } else {
        Text(
            text = "Walls ${com.measure.core.units.AreaFormatter.format(surfaces.netWallArea, viewModel.unitSystem())}" +
                " · volume ${com.measure.core.units.VolumeFormatter.format(surfaces.volume, viewModel.unitSystem())}",
            color = MeasureColours.Ink,
            fontSize = MeasureType.Label.fontSize,
            fontWeight = FontWeight.SemiBold,
        )
        if (surfaces.openingArea.squareMetres > 0.0) {
            Text(
                text = "After taking out " +
                    com.measure.core.units.AreaFormatter.format(surfaces.openingArea, viewModel.unitSystem()) +
                    " of doors and windows",
                color = MeasureColours.InkMuted,
                fontSize = MeasureType.Small.fontSize,
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
        color = MeasureColours.Ink,
        fontSize = MeasureType.Body.fontSize,
        fontWeight = FontWeight.SemiBold,
    )

    measurements.forEach { measurement ->
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MeasureColours.Sunk)
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
                color = MeasureColours.Ink,
                fontSize = MeasureType.Body.fontSize,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = measurement.mode.label,
                color = MeasureColours.InkMuted,
                fontSize = MeasureType.Small.fontSize,
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
private fun MeasureContent(viewModel: EditorViewModel) {
    when (val focus = viewModel.focus) {
        MeasureFocus.None -> Text(
            text = "Tap any dimension line for its size · " +
                "+ Distance measures between two points you choose",
            color = MeasureColours.InkMuted,
            style = MeasureType.Label,
        )

        is MeasureFocus.Dimension -> DimensionReadout(viewModel, focus)
        is MeasureFocus.Custom -> CustomDistanceReadout(viewModel, focus)
    }
}

/**
 * How much flooring and how much paint — docs/PRODUCT_PLAN.md §3, use cases 3 and 4.
 *
 * Two of the six reasons this app exists, and until now they had no surface. The numbers
 * were all present — a room's panel has shown its floor area and its net wall area since
 * M5 — but only one room at a time, behind a tap on that room, with the addition left to
 * the user. Nobody buys flooring for one room of three, so the arithmetic that mattered was
 * the arithmetic the app did not do.
 *
 * The answer is a single figure per material, set at reading size, with the inputs that
 * produced it as controls beside it rather than buried in a settings screen. Waste
 * percentage and coat count are not preferences; they are part of the question being asked,
 * and the answer has to move while they are changed.
 */
@Composable
private fun QuantitiesContent(viewModel: EditorViewModel) {
    val takeoff = viewModel.takeoff()

    if (takeoff.isEmpty) {
        Text(
            text = "Capture a room and its quantities appear here",
            color = MeasureColours.InkMuted,
            style = MeasureType.Label,
        )
        return
    }

    val areaUnit = viewModel.areaUnit()

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MeasureSpace.Base)) {
        MeasureReading(
            label = "Floor",
            value = viewModel.areaValue(takeoff.floorArea),
            modifier = Modifier.weight(1f),
            unit = areaUnit,
        )
        MeasureReading(
            label = "Walls, net",
            value = viewModel.areaValue(takeoff.netWallArea),
            modifier = Modifier.weight(1f),
            unit = areaUnit,
            // Amber when a room was left out, so the number is not read as a total when it
            // is only a subtotal. The sentence saying which rooms is below; the colour is
            // what stops the figure being copied down before the sentence is reached.
            colour = if (takeoff.wallsAreIncomplete) MeasureColours.Warning else MeasureColours.Ink,
        )
    }

    // Supporting figures rather than readings. This view has two answers — how much floor
    // to order and how much paint to buy — and giving perimeter and volume the same weight
    // as those would leave five numbers of equal size and no way to tell which is the one
    // that was asked for. They stay because skirting is bought by the metre and heating is
    // sized by the cubic one.
    Text(
        text = "Perimeter ${viewModel.formatLength(takeoff.perimeter.metres)} · " +
            "volume ${com.measure.core.units.VolumeFormatter.format(takeoff.volume, viewModel.unitSystem())}",
        color = MeasureColours.InkMuted,
        style = MeasureType.Small,
    )

    if (takeoff.wallsAreIncomplete) {
        Text(
            text = "No ceiling height for ${takeoff.roomsWithoutHeight.joinToString(", ")} — " +
                "walls and volume not counted for " +
                "${if (takeoff.roomsWithoutHeight.size == 1) "it" else "them"}. " +
                "Tap the room in Plan and set one.",
            color = MeasureColours.Warning,
            style = MeasureType.Small,
        )
    }

    MeasureRule()

    // --- flooring ---------------------------------------------------------------------

    MeasureTag("Flooring")
    Row(horizontalArrangement = Arrangement.spacedBy(MeasureSpace.Tight)) {
        Flooring.WASTE_OPTIONS.forEach { percent ->
            MeasureChip(
                label = "$percent%",
                onClick = { viewModel.selectWaste(percent) },
                selected = percent == viewModel.wastePercent,
            )
        }
    }
    MeasureReading(
        label = "Order",
        value = viewModel.areaValue(viewModel.flooringRequired(takeoff)),
        unit = areaUnit,
        large = true,
    )
    Text(
        text = "${viewModel.formatArea(takeoff.floorArea)} of floor plus " +
            "${viewModel.wastePercent}% for offcuts. Raise it for a diagonal or " +
            "herringbone lay, or for rooms that are not rectangles.",
        color = MeasureColours.InkMuted,
        style = MeasureType.Small,
    )

    MeasureRule()

    // --- paint ------------------------------------------------------------------------

    MeasureTag("Paint")
    Row(horizontalArrangement = Arrangement.spacedBy(MeasureSpace.Tight)) {
        Painting.COAT_OPTIONS.forEach { count ->
            MeasureChip(
                label = if (count == 1) "1 coat" else "$count coats",
                onClick = { viewModel.selectCoats(count) },
                selected = count == viewModel.coats,
            )
        }
    }
    MeasureChip(
        label = "Ceilings too",
        onClick = viewModel::togglePaintCeilings,
        selected = viewModel.paintCeilings,
    )
    MeasureReading(
        label = "Buy",
        value = viewModel.capacityValue(viewModel.paintRequired(takeoff)),
        unit = viewModel.capacityUnit(),
        large = true,
    )
    Text(
        text = "${viewModel.formatArea(viewModel.paintableArea(takeoff))} at " +
            "${viewModel.coats} ${if (viewModel.coats == 1) "coat" else "coats"}, " +
            "${Painting.TYPICAL_COVERAGE.toInt()} m² per litre. Check the tin — coverage " +
            "varies, and a wall changing colour drinks more.",
        color = MeasureColours.InkMuted,
        style = MeasureType.Small,
    )
    if (takeoff.openingArea.squareMetres > 0.0) {
        Text(
            text = "Doors and windows already taken out: " +
                viewModel.formatArea(takeoff.openingArea),
            color = MeasureColours.InkMuted,
            style = MeasureType.Small,
        )
    }

    MeasureRule()

    // --- by room ----------------------------------------------------------------------

    MeasureTag("By room")
    // Driven from the rooms rather than from `takeoff.rooms`, even though the two hold the
    // same values in the same order. A row needs the room's id to select it, and pairing a
    // list of quantities back up with a list of rooms by position is the kind of implicit
    // coupling that survives until someone sorts one of them.
    viewModel.current?.rooms.orEmpty().forEach { room ->
        RoomQuantityRow(
            viewModel = viewModel,
            room = room,
            selected = viewModel.selection == Selection.Room(room.id),
            onClick = { viewModel.select(Selection.Room(room.id)) },
        )
    }
}

/**
 * One room's line, and the link between the list and the drawing.
 *
 * Tapping a line selects the room on the plan, and a tap on the plan marks the line. Without
 * that the breakdown is a table of names, and "which one is the 12 m² one" is a question the
 * user has to answer by remembering what they called things.
 */
@Composable
private fun RoomQuantityRow(
    viewModel: EditorViewModel,
    room: SavedRoom,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val surfaces = room.surfaces

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MeasureShape.Edge))
            .background(if (selected) MeasureColours.Surface else Color.Transparent)
            .clickable(onClick = onClick)
            .touchTarget()
            .padding(horizontal = MeasureSpace.Tight, vertical = MeasureSpace.Hair),
        horizontalArrangement = Arrangement.spacedBy(MeasureSpace.Tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = room.name,
            color = if (selected) MeasureColours.Accent else MeasureColours.Ink,
            style = MeasureType.Label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = viewModel.formatArea(room.area),
            color = MeasureColours.Ink,
            style = MeasureType.ValueSmall,
        )
        Text(
            text = surfaces?.let { viewModel.formatArea(it.netWallArea) } ?: "no height",
            color = if (surfaces != null) MeasureColours.InkMuted else MeasureColours.Warning,
            style = if (surfaces != null) MeasureType.ValueSmall else MeasureType.Small,
        )
    }
}

@Composable
private fun DimensionReadout(viewModel: EditorViewModel, focus: MeasureFocus.Dimension) {
    val length = viewModel.focusedDimensionLength() ?: return

    Text(
        text = viewModel.formatLength(length),
        color = MeasureColours.Ink,
        fontSize = MeasureType.Title.fontSize,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = if (focus.isOverall) "Overall, across the whole plan" else "Between the marked corners",
        color = MeasureColours.InkMuted,
        fontSize = MeasureType.Small.fontSize,
    )
    Text(
        text = "The dashed lines show which part of the plan this covers.",
        color = MeasureColours.InkMuted,
        fontSize = MeasureType.Small.fontSize,
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
            fontSize = MeasureType.Label.fontSize,
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
        color = MeasureColours.Ink,
        fontSize = MeasureType.Title.fontSize,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = "Off the plan, not measured in the room",
        color = MeasureColours.Warning,
        fontSize = MeasureType.Small.fontSize,
    )
    Text(
        text = "${measurement.from.description} → ${measurement.to.description}",
        color = MeasureColours.InkMuted,
        fontSize = MeasureType.Small.fontSize,
    )
    if (measurement.isModelled) {
        Text(
            text = "One end sits on a corner the solver squared up, so part of this " +
                "distance is the model rather than the room.",
            color = MeasureColours.InkMuted,
            fontSize = MeasureType.Small.fontSize,
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
                color = MeasureColours.Ink,
                fontSize = MeasureType.Title.fontSize,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = measurement.mode.label + " measurement",
                color = MeasureColours.InkMuted,
                fontSize = MeasureType.Small.fontSize,
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
        color = MeasureColours.Ink,
        fontSize = MeasureType.Body.fontSize,
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = "Long-press and drag to move it. The room re-solves when you let go, so " +
            "right angles and locked walls still hold.",
        color = MeasureColours.InkMuted,
        fontSize = MeasureType.Small.fontSize,
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
                color = MeasureColours.Ink,
                fontSize = MeasureType.Body.fontSize,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (locked != null) {
                    "Locked to ${viewModel.formatLength(locked)}"
                } else {
                    "Measured ${viewModel.formatLength(current)}"
                },
                color = if (locked != null) MeasureColours.Ready else MeasureColours.InkMuted,
                fontSize = MeasureType.Small.fontSize,
            )
        }
        Text(viewModel.formatArea(room), color = MeasureColours.InkMuted, fontSize = MeasureType.Small.fontSize)
    }

    Text(
        text = "Measured this wall with a tape? Type the true length — the whole room " +
            "tightens around it.",
        color = MeasureColours.InkMuted,
        fontSize = MeasureType.Small.fontSize,
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
            color = if (openings.isEmpty()) MeasureColours.InkMuted else MeasureColours.Ink,
            fontSize = MeasureType.Small.fontSize,
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
                color = MeasureColours.Ink,
                fontSize = MeasureType.Label.fontSize,
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
            Text("×", color = MeasureColours.InkMuted, fontSize = MeasureType.Label.fontSize)
            Field(height, { height = it }, numeric = true, hint = "high", modifier = Modifier.weight(1f))
            Text("at", color = MeasureColours.InkMuted, fontSize = MeasureType.Label.fontSize)
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
) = MeasureButton(label, onClick, modifier, filled = highlighted, enabled = enabled)

private const val MESSAGE_DURATION_MS = 3000L
