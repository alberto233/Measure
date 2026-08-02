package com.measure.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.measure.core.data.SavedMeasurement
import com.measure.core.data.SavedRoom
import com.measure.core.designsystem.MeasureColours
import com.measure.core.geometry.Polygon
import com.measure.core.geometry.Segments
import com.measure.core.geometry.Vec2

/**
 * Maps between metres on the plan and pixels on the screen.
 *
 * Kept as a value rather than baked into the draw code because the gestures need it too:
 * a tap has to be turned back into metres before it can be asked which wall it landed on,
 * and doing that with a second, separately maintained copy of the transform is how a plan
 * ends up selecting the wall next to the one you touched.
 */
data class PlanCamera(
    val metresPerPixel: Double = 0.01,
    val centre: Vec2 = Vec2.ZERO,
) {
    fun toScreen(point: Vec2, size: IntSize): Offset = Offset(
        x = (size.width / 2.0 + (point.x - centre.x) / metresPerPixel).toFloat(),
        // Plan +y is "away"; screen +y is down.
        y = (size.height / 2.0 - (point.y - centre.y) / metresPerPixel).toFloat(),
    )

    fun toPlan(offset: Offset, size: IntSize): Vec2 = Vec2(
        x = centre.x + (offset.x - size.width / 2.0) * metresPerPixel,
        y = centre.y - (offset.y - size.height / 2.0) * metresPerPixel,
    )

    companion object {
        /** Fits [outlines] into [size] with a margin, for the initial view. */
        fun fitting(outlines: List<Vec2>, size: IntSize, marginPx: Int = 96): PlanCamera {
            if (outlines.isEmpty() || size.width == 0 || size.height == 0) return PlanCamera()

            val minX = outlines.minOf { it.x }
            val maxX = outlines.maxOf { it.x }
            val minY = outlines.minOf { it.y }
            val maxY = outlines.maxOf { it.y }

            val spanX = (maxX - minX).coerceAtLeast(MINIMUM_SPAN)
            val spanY = (maxY - minY).coerceAtLeast(MINIMUM_SPAN)
            val usableWidth = (size.width - 2 * marginPx).coerceAtLeast(1)
            val usableHeight = (size.height - 2 * marginPx).coerceAtLeast(1)

            return PlanCamera(
                metresPerPixel = maxOf(spanX / usableWidth, spanY / usableHeight),
                centre = Vec2((minX + maxX) / 2.0, (minY + maxY) / 2.0),
            )
        }

        private const val MINIMUM_SPAN = 0.5

        /** Zoom limits, in metres per pixel. Roughly 1 mm to 10 cm per pixel. */
        const val FINEST = 0.0005
        const val COARSEST = 0.1
    }
}

/**
 * The editable plan.
 *
 * Pan and pinch anywhere; tap a wall or a corner to select it; **long-press a corner and
 * drag** to move it. Drag-after-long-press rather than plain drag is deliberate: a plain
 * drag on a corner is indistinguishable from a pan that happened to start on one, and
 * accidentally reshaping a room while trying to scroll it is much worse than an extra
 * half-second before a deliberate move.
 */
@Composable
internal fun PlanCanvas(
    rooms: List<SavedRoom>,
    measurements: List<SavedMeasurement>,
    selection: Selection,
    dragging: EditorViewModel.DragState?,
    formatLength: (Double) -> String,
    onSelect: (Selection) -> Unit,
    onBeginDrag: (roomId: Long, index: Int, position: Vec2) -> Unit,
    onDrag: (Vec2) -> Unit,
    onEndDrag: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var camera by remember { mutableStateOf(PlanCamera()) }
    var fitted by remember { mutableStateOf(false) }

    // Measurements count towards the fit too: a project holding nothing but a single
    // distance would otherwise open on an empty canvas with the measurement off-screen.
    val allPoints = rooms.flatMap { it.outline } +
        measurements.flatMap { listOf(it.from.toFloorPlane(), it.to.toFloorPlane()) }

    // Fit once, when there is both something to show and somewhere to show it. Re-fitting
    // on every change would yank the view out from under someone who has zoomed in.
    if (!fitted && size != IntSize.Zero && allPoints.isNotEmpty()) {
        camera = PlanCamera.fitting(allPoints, size)
        fitted = true
    }

    val touchSlopMetres = { camera.metresPerPixel * TOUCH_SLOP_PX }

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    camera = camera.copy(
                        metresPerPixel = (camera.metresPerPixel / zoom)
                            .coerceIn(PlanCamera.FINEST, PlanCamera.COARSEST),
                        centre = camera.centre - Vec2(
                            pan.x * camera.metresPerPixel,
                            -pan.y * camera.metresPerPixel,
                        ),
                    )
                }
            }
            .pointerInput(rooms, camera) {
                detectTapGestures { offset ->
                    onSelect(hitTest(rooms, measurements, camera.toPlan(offset, size), touchSlopMetres()))
                }
            }
            .pointerInput(rooms, camera) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val plan = camera.toPlan(offset, size)
                        findCorner(rooms, plan, touchSlopMetres())?.let { (roomId, index) ->
                            onBeginDrag(roomId, index, plan)
                        }
                    },
                    onDrag = { change, _ -> onDrag(camera.toPlan(change.position, size)) },
                    onDragEnd = onEndDrag,
                    onDragCancel = onEndDrag,
                )
            },
    ) {
        // Standalone measurements, drawn under the rooms: they are reference marks rather
        // than structure, and a "will the sofa fit" line should not obscure a wall.
        measurements.forEach { measurement ->
            val from = camera.toScreen(measurement.from.toFloorPlane(), size)
            val to = camera.toScreen(measurement.to.toFloorPlane(), size)
            val selected = selection == Selection.Measurement(measurement.id)
            val colour = if (selected) MeasureColours.Sampling else MeasureColours.Idle

            drawLine(
                color = colour.copy(alpha = if (selected) 1f else 0.7f),
                start = from,
                end = to,
                strokeWidth = if (selected) 6f else 3f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                    floatArrayOf(14f, 10f),
                ),
            )
            listOf(from, to).forEach { drawCircle(colour, radius = 5f, center = it) }
        }

        rooms.forEach { room ->
            val outline = room.outline.toMutableList()
            if (dragging?.roomId == room.id && dragging.index in outline.indices) {
                outline[dragging.index] = dragging.position
            }
            if (outline.size < 3) return@forEach

            val screen = outline.map { camera.toScreen(it, size) }
            val isSelected = selection.roomId() == room.id

            val path = Path().apply {
                moveTo(screen.first().x, screen.first().y)
                screen.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(path, MeasureColours.Ready.copy(alpha = if (isSelected) 0.20f else 0.10f))

            outline.indices.forEach { index ->
                val from = screen[index]
                val to = screen[(index + 1) % screen.size]
                val locked = room.lockedLengths.containsKey(index)
                val selected = selection == Selection.Wall(room.id, index)

                drawLine(
                    color = when {
                        selected -> MeasureColours.Sampling
                        locked -> MeasureColours.Ready
                        else -> MeasureColours.OnScrim
                    },
                    start = from,
                    end = to,
                    strokeWidth = if (selected || locked) 6f else 3.5f,
                )
            }

            screen.forEachIndexed { index, point ->
                val selected = selection == Selection.Corner(room.id, index)
                drawCircle(
                    color = if (selected) MeasureColours.Sampling else MeasureColours.OnScrim,
                    radius = if (selected) 11f else 7f,
                    center = point,
                )
                drawCircle(MeasureColours.Surface, radius = if (selected) 5f else 3f, center = point)
            }
        }
    }

    // Wall lengths are text, so they are drawn as composables over the canvas rather than
    // with drawText — same reasoning as the AR overlay's labels.
    WallLabels(rooms, camera, size, dragging, selection, formatLength)
}

private fun Selection.roomId(): Long? = when (this) {
    is Selection.Wall -> roomId
    is Selection.Corner -> roomId
    is Selection.Measurement -> null
    Selection.None -> null
}

/**
 * What a tap landed on: a corner if one is within reach, otherwise a wall, otherwise the
 * room it fell inside, otherwise nothing.
 *
 * Corners win over walls because every corner is also on two walls, so the other order
 * would make corners unselectable.
 */
private fun hitTest(
    rooms: List<SavedRoom>,
    measurements: List<SavedMeasurement>,
    point: Vec2,
    reach: Double,
): Selection {
    findCorner(rooms, point, reach)?.let { (roomId, index) -> return Selection.Corner(roomId, index) }

    rooms.forEach { room ->
        val polygon = room.polygonOrNull() ?: return@forEach
        Segments.nearestEdge(polygon, point, reach)?.let { return Selection.Wall(room.id, it) }
    }

    measurements.forEach { measurement ->
        val distance = Segments.distanceToSegment(
            measurement.from.toFloorPlane(),
            measurement.to.toFloorPlane(),
            point,
        )
        if (distance <= reach) return Selection.Measurement(measurement.id)
    }

    rooms.forEach { room ->
        val polygon = room.polygonOrNull() ?: return@forEach
        if (Segments.contains(polygon, point)) return Selection.Wall(room.id, 0)
    }
    return Selection.None
}

private fun findCorner(rooms: List<SavedRoom>, point: Vec2, reach: Double): Pair<Long, Int>? {
    rooms.forEach { room ->
        val polygon = room.polygonOrNull() ?: return@forEach
        Segments.nearestVertex(polygon, point, reach)?.let { return room.id to it }
    }
    return null
}

internal fun SavedRoom.polygonOrNull(): Polygon? =
    if (outline.size >= 3) Polygon(outline) else null

/** Fingers are blunt; this is the radius within which a tap counts as "on" something. */
private const val TOUCH_SLOP_PX = 28.0
