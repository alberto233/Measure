package com.measure.feature.capture

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.measure.ar.ArPhase
import com.measure.ar.ArScene
import com.measure.ar.CaptureMode
import com.measure.ar.ArSurfaceView
import com.measure.ar.ArUiState
import com.measure.ar.MeasureArController
import com.measure.core.units.UnitSystem
import kotlinx.coroutines.delay
import com.measure.core.designsystem.MeasureColours
import com.measure.core.designsystem.MeasureType

/**
 * The M1 capture screen: point at something, tap, point at the other end, tap, read the
 * distance with its tolerance.
 *
 * Layered as camera → AR content (drawn in GL by `:ar`) → this 2D overlay. Text and
 * controls live here rather than in the 3D scene because Compose already renders text
 * beautifully and an OpenGL font atlas would be a lot of code to do it worse.
 */
@Composable
fun CaptureScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    /** Continue an existing project, or null to start a new one on the first save. */
    projectId: Long? = null,
    viewModel: CaptureViewModel = viewModel(),
) {
    LaunchedEffect(projectId) { viewModel.attachToProject(projectId) }

    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val state by viewModel.controller.state.collectAsStateWithLifecycle()

    // Held here rather than created inside AndroidView's factory because the lifecycle
    // effect below has to call onResume/onPause on this exact instance, in an order that
    // must interleave correctly with the session's own.
    val surfaceView = remember(viewModel) { ArSurfaceView(context, viewModel.controller) }

    CameraPermissionAndLifecycle(viewModel.controller, surfaceView, activity)

    // The reticle position lives on the render thread's state; proximity to the starting
    // corner is a view-model concern. This is the seam between them.
    LaunchedEffect(state.target, viewModel.captureMode, viewModel.roomCorners.size) {
        viewModel.updateStartProximity(state.target?.position)
    }

    LaunchedEffect(state.ceilingHeight) { viewModel.noteCeilingHeight(state.ceilingHeight) }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            // Detached first because this view outlives the AndroidView node that hosts
            // it: it is remembered across recompositions so the lifecycle effect can
            // drive it. Handing an already-parented view to a new host throws.
            factory = {
                (surfaceView.parent as? ViewGroup)?.removeView(surfaceView)
                surfaceView
            },
            modifier = Modifier.fillMaxSize(),
        )

        MeasurementLabels(state, viewModel)

        Reticle(
            ready = state.canCapture,
            hasTarget = state.target != null,
            samplingProgress = state.sampling?.fraction,
            // Only once a measurement is under way. Before that there is no trajectory to
            // hold, and permanent crosshairs over a camera feed are just clutter.
            showAlignmentAxes = state.preview != null,
            modifier = Modifier.fillMaxSize(),
        )

        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(state, viewModel, onExit, Modifier.align(Alignment.TopCenter))

            if (viewModel.captureMode == CaptureMode.ROOM) {
                RoomMinimap(
                    corners = viewModel.planOutline(),
                    preview = state.target?.position?.toFloorPlane()
                        .takeIf { !viewModel.isRoomClosed },
                    closed = viewModel.isRoomClosed,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 108.dp, end = 16.dp),
                )
            }

            BottomBar(state, viewModel, Modifier.align(Alignment.BottomCenter))
            Notice(viewModel, Modifier.align(Alignment.Center).padding(top = 140.dp))
        }

        state.failure?.let { failure ->
            SessionProblem(
                title = failure.message,
                detail = failure.detail,
                actionLabel = if (failure.recoverable) "Try again" else null,
                onAction = { activity?.let { viewModel.controller.resume(it) } },
                onExit = onExit,
            )
        }
    }
}

// --- AR surface and lifecycle -------------------------------------------------------

/**
 * Ties the ARCore session and the GL surface to the composition's lifecycle, and asks for
 * the camera.
 *
 * The **ordering** is the part that matters and the part that is easy to get wrong.
 * Resuming: the session first, then the surface — so the render thread never calls
 * `Session.update()` on a session that is not running. Pausing: the surface first, then
 * the session — for the same reason in reverse. Getting this backwards is one of the
 * classic ARCore crashes, and it is exactly the kind of thing this app treats as a
 * feature rather than plumbing (docs/PRODUCT_PLAN.md §5).
 */
@Composable
private fun CameraPermissionAndLifecycle(
    controller: MeasureArController,
    surfaceView: ArSurfaceView,
    activity: Activity?,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && activity != null) {
            controller.resume(activity)
            surfaceView.onResume()
        }
    }

    DisposableEffect(lifecycleOwner, surfaceView, activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (activity != null) {
                        controller.resume(activity)
                        surfaceView.onResume()
                        if (controller.state.value.phase == ArPhase.NEEDS_CAMERA_PERMISSION) {
                            // Asked here rather than on first composition so the prompt
                            // arrives with the screen visible behind it.
                            permissionLauncher.launch(android.Manifest.permission.CAMERA)
                        }
                    }
                }

                Lifecycle.Event.ON_PAUSE -> {
                    surfaceView.onPause()
                    controller.pause()
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

// --- overlay pieces -----------------------------------------------------------------

@Composable
private fun TopBar(
    state: ArUiState,
    viewModel: CaptureViewModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton("Done", onClick = onExit)
            TrackingChip(state.tracking, state.depthEnabled)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.torchSupported) {
                    PillButton(
                        label = if (state.torchOn) "Torch on" else "Torch",
                        highlighted = state.torchOn,
                        onClick = { viewModel.setTorch(!state.torchOn) },
                    )
                }
                PillButton(
                    label = if (viewModel.unitSystem == UnitSystem.METRIC) "m" else "ft",
                    onClick = viewModel::toggleUnits,
                )
            }
        }

        AimAdvice(
            advice = state.rangeAdvice,
            source = state.target?.source,
            rangeText = state.target?.let { "${viewModel.formatLength(it.range)} away" },
            offFloor = state.offFloor,
        )
    }
}

@Composable
private fun BottomBar(
    state: ArUiState,
    viewModel: CaptureViewModel,
    modifier: Modifier = Modifier,
) {
    val room = viewModel.captureMode == CaptureMode.ROOM

    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (room) RoomReadout(state, viewModel) else LatestMeasurement(viewModel)

        // Level and plumb only mean anything for a free-standing distance; a room corner
        // is already constrained, by the floor.
        if (!room) ModeSelector(viewModel.mode, viewModel::selectMode)

        CaptureModeSelector(viewModel.captureMode, viewModel::selectCaptureMode)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(
                label = "Undo",
                enabled = if (room) {
                    viewModel.canUndoRoom
                } else {
                    viewModel.pending != null || viewModel.segments.isNotEmpty()
                },
                onClick = viewModel::undo,
            )
            CaptureButton(
                enabled = if (room) state.canCaptureCorner && !viewModel.isRoomClosed else state.canCapture,
                sampling = state.sampling != null,
                onClick = viewModel::capture,
            )
            if (room) {
                PillButton(
                    label = if (viewModel.isRoomClosed) "New room" else "Close",
                    enabled = viewModel.isRoomClosed || viewModel.canCloseRoom,
                    onClick = {
                        if (viewModel.isRoomClosed) viewModel.restartRoom() else viewModel.closeRoom()
                    },
                )
            } else {
                PillButton(
                    label = if (viewModel.showPlanes) "Hide planes" else "Show planes",
                    onClick = viewModel::togglePlanes,
                )
            }
        }
    }
}

/**
 * The room readout: guidance while capturing, the result once closed.
 *
 * The area comes with its own caveat when the loop closed badly. A number presented
 * without that context is the thing this app is built not to do.
 */
@Composable
private fun RoomReadout(
    state: ArUiState,
    viewModel: CaptureViewModel,
    modifier: Modifier = Modifier,
) {
    val solution = viewModel.roomSolution

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MeasureColours.Scrim)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (solution != null) {
            Text(
                text = viewModel.formatArea(solution),
                color = MeasureColours.OnScrim,
                fontSize = MeasureType.Display.fontSize,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Perimeter ${viewModel.formatLength(solution.perimeter.metres)}" +
                    " · ${solution.polygon.size} walls" +
                    (viewModel.detectedCeilingHeight?.let { " · ${viewModel.formatLength(it)} high" } ?: ""),
                color = MeasureColours.OnScrimMuted,
                fontSize = MeasureType.Small.fontSize,
            )
            Text(
                text = when {
                    // Shut with the button rather than by re-reading the first corner:
                    // nothing checked the walk, so there is no misclosure to quote and
                    // "0.0%" would be reporting a check that never happened.
                    !solution.closure.wasAdjusted -> "Closed without a second reading — drift unmeasured"
                    solution.isReliable ->
                        "Closed to ${viewModel.percent(solution.closure.relativeError)} of perimeter"

                    else ->
                        "Drift ${viewModel.percent(solution.closure.relativeError)} — re-measure for a better plan"
                },
                color = if (solution.isReliable) MeasureColours.OnScrimMuted else MeasureColours.Warning,
                fontSize = MeasureType.Small.fontSize,
            )
            return@Column
        }

        val corners = viewModel.roomCorners.size
        Text(
            text = when {
                state.floor?.isEstablished != true -> "Finding the floor…"
                corners == 0 -> "Tap the first corner"
                viewModel.isNearStartCorner -> "Tap to close the room"
                viewModel.isApproachingStart -> "Back near the start"
                else -> "$corners ${if (corners == 1) "corner" else "corners"}"
            },
            color = if (viewModel.isNearStartCorner) MeasureColours.Ready else MeasureColours.OnScrim,
            fontSize = MeasureType.Title.fontSize,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = when {
                state.floor?.isEstablished != true -> "Point at the floor and move slowly"
                state.offFloor -> "Corners come from the floor, not from what is stacked on it"
                corners == 0 -> "Then walk round, tapping each corner"
                viewModel.isNearStartCorner -> "Closing here measures the drift and corrects the plan"
                // Only the first corner itself closes the room. A tap anywhere else here
                // is a corner, which is what makes an alcove beside the doorway you began
                // at possible to record at all.
                viewModel.isApproachingStart -> "Aim at the first corner to close, or tap Close"
                corners < 3 -> "Keep going round the room"
                else -> "Return to the first corner to close"
            },
            color = MeasureColours.OnScrimMuted,
            fontSize = MeasureType.Small.fontSize,
        )
    }
}

/** The headline number. The reason the user opened the app. */
@Composable
private fun LatestMeasurement(viewModel: CaptureViewModel, modifier: Modifier = Modifier) {
    val latest = viewModel.segments.lastOrNull() ?: return

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MeasureColours.Scrim)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = viewModel.format(latest),
            color = MeasureColours.OnScrim,
            fontSize = MeasureType.Display.fontSize,
            fontWeight = FontWeight.Bold,
        )
        val suffix = if (viewModel.segments.size > 1) " · ${viewModel.segments.size} measurements" else ""
        Text(
            text = latest.mode.label + suffix,
            color = MeasureColours.OnScrimMuted,
            fontSize = MeasureType.Small.fontSize,
        )
    }
}

/**
 * Value labels pinned to the midpoint of each measurement in the world.
 *
 * The render thread has already projected the world positions to pixels — it holds the
 * view-projection matrix — so this only has to place composables at those coordinates,
 * centred on them, which is what the custom [Layout] does.
 */
@Composable
private fun MeasurementLabels(state: ArUiState, viewModel: CaptureViewModel) {
    if (state.anchors.isEmpty()) return

    val byId = remember(viewModel.segments.toList()) { viewModel.segments.associateBy { it.id } }

    Layout(
        content = {
            state.anchors.forEach { anchor ->
                if (anchor.id == ArScene.PREVIEW_ANCHOR_ID) {
                    val preview = state.preview
                    if (preview != null) {
                        MeasurementLabel(viewModel.formatLength(preview.lengthMetres), emphasised = true)
                    } else {
                        Box(Modifier)
                    }
                } else {
                    val segment = byId[anchor.id]
                    if (segment != null) {
                        MeasurementLabel(viewModel.format(segment), emphasised = false)
                    } else {
                        Box(Modifier)
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        // Lifted above the midpoint rather than centred on it. The reticle is at the
        // exact centre of the screen, and aiming at the middle of your own measurement
        // is the normal thing to do, so a centred label covers the thing you are aiming
        // with at precisely the moment you need it.
        val lift = LABEL_LIFT_DP.dp.roundToPx()
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val anchor = state.anchors.getOrNull(index) ?: return@forEachIndexed
                placeable.place(
                    x = (anchor.x - placeable.width / 2f).toInt().coerceIn(
                        0,
                        (constraints.maxWidth - placeable.width).coerceAtLeast(0),
                    ),
                    y = (anchor.y - placeable.height / 2f - lift).toInt().coerceIn(
                        0,
                        (constraints.maxHeight - placeable.height).coerceAtLeast(0),
                    ),
                )
            }
        }
    }
}

/** A transient message. Auto-dismisses, because nothing here is worth a tap to clear. */
@Composable
private fun Notice(viewModel: CaptureViewModel, modifier: Modifier = Modifier) {
    val notice = viewModel.notice ?: return

    LaunchedEffect(notice) {
        delay(NOTICE_DURATION_MS)
        viewModel.dismissNotice()
    }

    Text(
        text = notice.text,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MeasureColours.Scrim)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        color = when (notice) {
            is CaptureNotice.Warning -> MeasureColours.Warning
            is CaptureNotice.Advice -> MeasureColours.OnScrim
        },
        fontSize = MeasureType.Label.fontSize,
        textAlign = TextAlign.Center,
    )
}

/**
 * The full-screen failure state.
 *
 * Every ARCore failure lands here with a sentence naming the cause and, where one exists,
 * a way out. Silently showing a black screen is what makes users report an AR app as
 * crashing even when it has not.
 */
@Composable
private fun SessionProblem(
    title: String,
    detail: String?,
    actionLabel: String?,
    onAction: () -> Unit,
    onExit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF00E1013))
            .safeDrawingPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = MeasureColours.OnScrim,
            fontSize = MeasureType.Title.fontSize,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        detail?.let {
            Text(
                text = it,
                modifier = Modifier.padding(top = 10.dp),
                color = MeasureColours.OnScrimMuted,
                fontSize = MeasureType.Body.fontSize,
                textAlign = TextAlign.Center,
            )
        }
        Row(
            Modifier.padding(top = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (actionLabel != null) PillButton(actionLabel, onClick = onAction)
            PillButton("Back", onClick = onExit)
        }
    }
}

private const val NOTICE_DURATION_MS = 2600L

/** Enough to clear the reticle, whose outer radius is 26 dp. */
private const val LABEL_LIFT_DP = 46
