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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.measure.core.data.SavedMeasurement
import com.measure.core.data.SavedPlanMeasurement
import com.measure.core.data.SavedRoom
import com.measure.core.designsystem.MeasureColours
import com.measure.core.geometry.OpeningKind
import com.measure.core.geometry.Polygon
import com.measure.core.geometry.Segments
import com.measure.core.geometry.Vec2
import com.measure.core.geometry.plan.DimensionChain
import com.measure.core.geometry.plan.ResolvedPoint
import com.measure.core.geometry.plan.SnapKind
import kotlin.math.atan2
import kotlin.math.hypot

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
        /**
         * @param marginPx enough clear space for the dimension strings, whose overall
         *   line stands 92 px off the plan with a label above it. Fitting tighter than
         *   that puts the overall dimension off the edge of the screen the moment
         *   measuring is switched on, and the fit deliberately happens only once.
         */
        fun fitting(outlines: List<Vec2>, size: IntSize, marginPx: Int = 132): PlanCamera {
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
                // Clamped to the same range pinching allows, so the view never opens at a
                // zoom the user cannot get back to.
                metresPerPixel = maxOf(spanX / usableWidth, spanY / usableHeight)
                    .coerceIn(FINEST, COARSEST),
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
    planMeasurements: List<SavedPlanMeasurement>,
    selection: Selection,
    dragging: EditorViewModel.DragState?,
    movingRoom: EditorViewModel.RoomMove?,
    /** Non-null while measuring: the mode, and the first end if one is down. */
    measuring: Boolean,
    drawing: Boolean,
    focus: MeasureFocus,
    pendingEnd: ResolvedPoint?,
    /** Drawn only while measuring, which is when they are what was asked for. */
    dimensionChains: List<DimensionChain>,
    formatLength: (Double) -> String,
    onSelect: (Selection) -> Unit,
    onFocus: (MeasureFocus) -> Unit,
    onMeasureTap: (point: Vec2, reach: Double) -> Unit,
    onBeginDrag: (roomId: Long, index: Int, position: Vec2) -> Unit,
    onDrag: (Vec2) -> Unit,
    onEndDrag: () -> Unit,
    onBeginRoomMove: (roomId: Long, at: Vec2) -> Unit,
    onRoomMove: (Vec2) -> Unit,
    onEndRoomMove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var camera by remember { mutableStateOf(PlanCamera()) }
    var fitted by remember { mutableStateOf(false) }

    // Measurements count towards the fit too: a project holding nothing but a single
    // distance would otherwise open on an empty canvas with the measurement off-screen.
    val allPoints = rooms.flatMap { it.outline } +
        measurements.flatMap { listOf(it.from.toFloorPlane(), it.to.toFloorPlane()) } +
        planMeasurements.mapNotNull { it.measurement }
            .flatMap { listOf(it.from.position, it.to.position) }

    // Fit once, when there is both something to show and somewhere to show it. Re-fitting
    // on every change would yank the view out from under someone who has zoomed in.
    if (!fitted && size != IntSize.Zero && allPoints.isNotEmpty()) {
        camera = PlanCamera.fitting(allPoints, size)
        fitted = true
    }

    val touchSlopMetres = { camera.metresPerPixel * TOUCH_SLOP_PX }

    // How far a guide has to run to cross the whole plan, in metres. Computed from the
    // rooms alone so a stray measurement cannot stretch it across the screen.
    val planBounds = rooms.flatMap { it.outline }

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
            .pointerInput(rooms, camera, measuring, drawing) {
                detectTapGestures { offset ->
                    when {
                        // Placing points is a deliberate sub-mode, so a tap here can only
                        // mean one thing and never has to be guessed at.
                        measuring && drawing ->
                            onMeasureTap(camera.toPlan(offset, size), touchSlopMetres())

                        // Otherwise a tap in the measure view asks to *read* something.
                        // Dimension runs are tested first because they lie outside the
                        // plan, where nothing else is.
                        measuring -> onFocus(
                            hitDimension(dimensionChains, camera, size, offset)
                                ?: hitPlanMeasurement(planMeasurements, camera, size, offset)
                                ?: MeasureFocus.None,
                        )

                        else -> onSelect(
                            hitTest(rooms, measurements, camera.toPlan(offset, size), touchSlopMetres()),
                        )
                    }
                }
            }
            .pointerInput(rooms, camera, measuring) {
                // A long press on a corner reshapes the room; a long press on the body of
                // one slides the whole thing. Corner first, because every corner is also
                // inside its room and the other order would make corners unreachable.
                //
                // Only outside the measure view: there a long press has no meaning, and a
                // plan that rearranges itself while someone is reading a dimension off it
                // is the plan moving without being asked.
                var target: Long? = null
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        // Cleared first, so a press that catches nothing cannot inherit
                        // the previous gesture's answer.
                        target = null
                        val plan = camera.toPlan(offset, size)
                        val corner = if (measuring) null else findCorner(rooms, plan, touchSlopMetres())
                        when {
                            corner != null -> onBeginDrag(corner.first, corner.second, plan)
                            measuring -> Unit
                            else -> findRoom(rooms, plan)?.let {
                                target = it
                                onBeginRoomMove(it, plan)
                            }
                        }
                    },
                    onDrag = { change, _ ->
                        val plan = camera.toPlan(change.position, size)
                        if (target == null) onDrag(plan) else onRoomMove(plan)
                    },
                    onDragEnd = { if (target == null) onEndDrag() else onEndRoomMove() },
                    onDragCancel = { if (target == null) onEndDrag() else onEndRoomMove() },
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

            if (measurement.isVerticalOnPlan) {
                // A plumb measurement is almost all height, and a floor plan discards
                // height. Drawn as a line it collapsed to nothing, so a project holding
                // two room heights showed two bare dots on an empty canvas.
                drawHeightMark(
                    centre = Offset((from.x + to.x) / 2f, (from.y + to.y) / 2f),
                    colour = colour,
                    selected = selected,
                )
            } else {
                drawLine(
                    color = colour.copy(alpha = if (selected) 1f else 0.7f),
                    start = from,
                    end = to,
                    strokeWidth = if (selected) 6f else 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
                )
                listOf(from, to).forEach { drawCircle(colour, radius = 5f, center = it) }
            }
        }

        rooms.forEach { room ->
            // Live under the finger for both gestures, so the room being moved is the one
            // being looked at rather than a ghost that catches up when the finger lifts.
            val shift = movingRoom?.takeIf { it.roomId == room.id }?.offset
            val outline = (if (shift == null) room.outline else room.outline.map { it + shift })
                .toMutableList()
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
            drawPath(path, MeasureColours.OnScrim.copy(alpha = if (isSelected) 0.14f else 0.06f))

            val polygon = Polygon(outline)

            outline.indices.forEach { index ->
                val from = screen[index]
                val to = screen[(index + 1) % screen.size]
                val locked = room.lockedLengths.containsKey(index)
                val selected = selection == Selection.Wall(room.id, index)
                val wallStroke = if (selected || locked) 6f else 3.5f

                drawLine(
                    color = when {
                        selected -> MeasureColours.Accent
                        locked -> MeasureColours.Ready
                        else -> MeasureColours.OnScrim
                    },
                    start = from,
                    end = to,
                    strokeWidth = wallStroke,
                )

                val openings = room.openings[index].orEmpty()
                if (openings.isEmpty()) return@forEach

                val wallLength = outline[index].distanceTo(outline[(index + 1) % outline.size])
                val wallPixels = hypot(to.x - from.x, to.y - from.y)
                if (wallLength <= 0.0 || wallPixels < 1f) return@forEach

                val along = Offset((to.x - from.x) / wallPixels, (to.y - from.y) / wallPixels)
                val inward = Segments.inwardNormal(polygon, index)?.toScreenVector() ?: return@forEach

                openings.forEach { saved ->
                    val startFraction = (saved.opening.offset / wallLength).coerceIn(0.0, 1.0)
                    val endFraction =
                        ((saved.opening.offset + saved.opening.width) / wallLength).coerceIn(0.0, 1.0)

                    drawOpening(
                        kind = saved.opening.kind,
                        jambA = lerp(from, to, startFraction.toFloat()),
                        jambB = lerp(from, to, endFraction.toFloat()),
                        along = along,
                        inward = inward,
                        wallStroke = wallStroke,
                    )
                }
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

        // Distances drawn on the plan, over the rooms because they are usually measured
        // *to* a wall and would otherwise be hidden by it.
        // Only in the measure view. On the plan itself these are annotations competing
        // with the drawing; here they are the subject.
        if (measuring) {
            planMeasurements.forEach { saved ->
                val measurement = saved.measurement ?: return@forEach
                val active = focus == MeasureFocus.Custom(saved.id)
                val from = camera.toScreen(measurement.from.position, size)
                val to = camera.toScreen(measurement.to.position, size)
                val colour = if (active) MeasureColours.Sampling else MeasureColours.Warning

                drawLine(
                    color = colour.copy(alpha = if (active) 1f else 0.55f),
                    start = from,
                    end = to,
                    strokeWidth = if (active) 5f else 3f,
                )
                drawEndMark(from, measurement.from.kind, colour, active)
                drawEndMark(to, measurement.to.kind, colour, active)
            }
        }

        // Dimension strings, drawn outside the plan in the way a drawing does it: the
        // overall span with the runs between corners beneath, on witness lines clear of
        // the geometry. Outside rather than over, so the plan stays readable.
        dimensionChains.forEachIndexed { index, chain ->
            drawDimensionChain(chain, camera, size, focus.runIn(index))
        }
        // The guide: the focused run projected back across the plan, so it is obvious
        // which stretch of building the number belongs to. This is what an architect does
        // with a straightedge when checking a dimension against the drawing.
        (focus as? MeasureFocus.Dimension)?.let { target ->
            dimensionChains.getOrNull(target.chain)?.let { chain ->
                drawDimensionGuide(chain, target, camera, size, planBounds)
            }
        }

        // The half-placed end, while measuring. Big, and marked with what it caught, so a
        // mis-tap is visible before the second tap builds a number on top of it.
        pendingEnd?.let { end ->
            val point = camera.toScreen(end.position, size)
            drawCircle(MeasureColours.Sampling.copy(alpha = 0.25f), radius = 20f, center = point)
            drawEndMark(point, end.kind, MeasureColours.Sampling, selected = true)
        }
    }

    // Lengths are text, so they are drawn as composables over the canvas rather than with
    // drawText — same reasoning as the AR overlay's labels.
    PlanLabels(
        wallLabels(rooms, camera, size, dragging, selection, formatLength) +
            measurementLabels(measurements, camera, size, selection, formatLength) +
            planMeasurementLabels(planMeasurements, camera, size, measuring, focus, formatLength) +
            dimensionLabels(dimensionChains, camera, size, focus, formatLength),
    )
}

private fun lerp(from: Offset, to: Offset, t: Float) = Offset(
    x = from.x + (to.x - from.x) * t,
    y = from.y + (to.y - from.y) * t,
)

// --- symbols --------------------------------------------------------------------------

/**
 * Draws an opening the way a floor plan does, rather than as a coloured stripe.
 *
 * The point is instant recognition. A door and a window drawn as two differently tinted
 * segments of wall are told apart only by remembering which colour meant which; the
 * conventional symbols — a swing arc for a door, a framed gap for a window — are the
 * notation everyone who has ever looked at a plan already reads, and they carry
 * information a stripe cannot, namely which way the door opens.
 */
private fun DrawScope.drawOpening(
    kind: OpeningKind,
    jambA: Offset,
    jambB: Offset,
    /** Unit vector along the wall, from [jambA] towards [jambB]. */
    along: Offset,
    /** Unit vector across the wall, pointing into the room. */
    inward: Offset,
    wallStroke: Float,
) {
    val width = hypot(jambB.x - jambA.x, jambB.y - jambA.y)
    if (width < MINIMUM_SYMBOL_PX) return

    // Cut the wall away. Every other mark sits on this gap, and on a real plan the gap
    // alone already says "there is a hole in this wall here".
    drawLine(MeasureColours.Surface, jambA, jambB, strokeWidth = wallStroke + GAP_OVERDRAW_PX)

    // Jambs across the wall, so the opening reads as a framed hole rather than a stretch
    // of missing wall.
    listOf(jambA, jambB).forEach { jamb ->
        drawLine(
            color = MeasureColours.OnScrim,
            start = jamb - inward * JAMB_HALF_PX,
            end = jamb + inward * JAMB_HALF_PX,
            strokeWidth = 3f,
        )
    }

    when (kind) {
        OpeningKind.DOOR -> {
            // Leaf standing open at right angles, with the quarter-circle it sweeps. The
            // leaf is hinged at the near jamb because that is where the opening's offset
            // is measured from, so the symbol and the number agree.
            val leaf = jambA + inward * width
            drawLine(MeasureColours.OnScrimMuted, jambA, leaf, strokeWidth = 4f)
            drawArc(
                color = MeasureColours.OnScrimMuted.copy(alpha = 0.75f),
                startAngle = screenAngle(inward),
                sweepAngle = quarterTurn(inward, along),
                useCenter = false,
                topLeft = Offset(jambA.x - width, jambA.y - width),
                size = Size(width * 2f, width * 2f),
                style = Stroke(width = 2f),
            )
        }

        OpeningKind.WINDOW -> {
            // The frame seen from above: two lines spanning the gap, inside the jambs.
            listOf(-GLAZING_HALF_PX, GLAZING_HALF_PX).forEach { offset ->
                drawLine(
                    color = MeasureColours.Idle,
                    start = jambA + inward * offset,
                    end = jambB + inward * offset,
                    strokeWidth = 2.5f,
                )
            }
        }

        // An archway is a hole with nothing in it, and that is exactly how it is drawn.
        OpeningKind.PASSAGE -> Unit
    }
}

/**
 * One dimension string: a line of ticks outside the plan, with witness lines back to it.
 *
 * Everything here is in pixels rather than metres on purpose. A dimension string is
 * annotation, not geometry — it should stand the same distance clear of the drawing and
 * carry the same tick size however far the plan is zoomed, exactly as it would on paper.
 */
private fun DrawScope.drawDimensionChain(
    chain: DimensionChain,
    camera: PlanCamera,
    size: IntSize,
    /** The run of this chain being read, if any: null, a segment index, or OVERALL. */
    activeRun: Int?,
) {
    if (chain.ticks.size < 2) return

    // Plan +y is "away" and screen +y is down, so the normal flips on the way to pixels.
    val outward = Offset(-chain.normal.x.toFloat(), chain.normal.y.toFloat())
    val runLine = outward * DIMENSION_RUN_OFFSET_PX
    val overallLine = outward * DIMENSION_OVERALL_OFFSET_PX

    val ends = chain.ticks.map { camera.toScreen(chain.pointAt(it), size) }

    // Witness lines: from the plan edge out past the furthest dimension line, so each
    // tick is visibly the corner it came from and not an unexplained mark.
    ends.forEach { anchor ->
        drawLine(
            color = MeasureColours.OnScrimMuted.copy(alpha = 0.45f),
            start = anchor + outward * DIMENSION_WITNESS_GAP_PX,
            end = anchor + overallLine + outward * DIMENSION_WITNESS_OVERRUN_PX,
            strokeWidth = 1.5f,
        )
    }

    fun tick(at: Offset, along: Offset) {
        // The 45-degree slash of a drawing, rather than an arrowhead: it stays legible at
        // any size and does not fill in when two ticks are close together.
        val slash = Offset(along.x + outward.x, along.y + outward.y) * DIMENSION_TICK_PX
        drawLine(MeasureColours.OnScrim, at - slash, at + slash, strokeWidth = 2f)
    }

    val direction = (ends.last() - ends.first()).let {
        val length = hypot(it.x, it.y)
        if (length < 1f) Offset(1f, 0f) else Offset(it.x / length, it.y / length)
    }

    // The runs between corners, each drawn separately so the one being read can be picked
    // out. Unread runs are quiet: the lines are there to be aimed at, not to be studied.
    chain.segments.forEachIndexed { index, _ ->
        val active = activeRun == index
        drawLine(
            color = if (active) MeasureColours.Sampling else MeasureColours.OnScrimMuted,
            start = ends[index] + runLine,
            end = ends[index + 1] + runLine,
            strokeWidth = if (active) 3f else 1.5f,
        )
    }
    ends.forEach { tick(it + runLine, direction) }

    // And the overall, further out. Only when it says something the runs do not.
    if (chain.segments.size > 1) {
        val active = activeRun == MeasureFocus.Dimension.OVERALL
        drawLine(
            color = if (active) MeasureColours.Sampling else MeasureColours.OnScrimMuted,
            start = ends.first() + overallLine,
            end = ends.last() + overallLine,
            strokeWidth = if (active) 3f else 1.5f,
        )
        tick(ends.first() + overallLine, direction)
        tick(ends.last() + overallLine, direction)
    }
}

/**
 * Projects the run being read back across the plan.
 *
 * A dimension sitting in the margin says how long something is without saying *which*
 * something. Two dashed lines at the run's ends, carried across the drawing, answer that
 * without a word — and they are the only thing on screen besides the number, which is the
 * point of showing one at a time.
 */
private fun DrawScope.drawDimensionGuide(
    chain: DimensionChain,
    target: MeasureFocus.Dimension,
    camera: PlanCamera,
    size: IntSize,
    planPoints: List<Vec2>,
) {
    if (planPoints.isEmpty()) return

    val (fromTick, toTick) = if (target.isOverall) {
        chain.ticks.first() to chain.ticks.last()
    } else {
        val segment = chain.segments.getOrNull(target.segment) ?: return
        segment.from to segment.to
    }

    // How far the plan reaches across the guide's direction, so the line spans it exactly
    // rather than being given an arbitrary length that is wrong at some zoom.
    val depths = planPoints.map { it dot chain.normal }
    val near = depths.min()
    val far = depths.max()

    listOf(fromTick, toTick).forEach { along ->
        val start = camera.toScreen(chain.direction * along + chain.normal * near, size)
        val end = camera.toScreen(chain.direction * along + chain.normal * far, size)
        drawLine(
            color = MeasureColours.Sampling.copy(alpha = 0.55f),
            start = start,
            end = end,
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
        )
    }
}

/** Which run of chain [index] is being read, if this focus is on that chain at all. */
internal fun MeasureFocus.runIn(index: Int): Int? =
    (this as? MeasureFocus.Dimension)?.takeIf { it.chain == index }?.segment

/**
 * Which dimension run a tap landed on, tested in pixels.
 *
 * Pixels rather than metres because a dimension string is annotation: it stands the same
 * distance off the drawing however far the plan is zoomed, so where it *is* is a screen
 * question and asking it in metres would make the target move with the zoom.
 */
private fun hitDimension(
    chains: List<DimensionChain>,
    camera: PlanCamera,
    size: IntSize,
    tap: Offset,
): MeasureFocus.Dimension? {
    chains.forEachIndexed { chainIndex, chain ->
        if (chain.ticks.size < 2) return@forEachIndexed
        val outward = Offset(-chain.normal.x.toFloat(), chain.normal.y.toFloat())
        val ends = chain.ticks.map { camera.toScreen(chain.pointAt(it), size) }

        chain.segments.indices.forEach { index ->
            val from = ends[index] + outward * DIMENSION_RUN_OFFSET_PX
            val to = ends[index + 1] + outward * DIMENSION_RUN_OFFSET_PX
            if (distanceToSegmentPx(from, to, tap) <= DIMENSION_TOUCH_SLOP_PX) {
                return MeasureFocus.Dimension(chainIndex, index)
            }
        }

        if (chain.segments.size > 1) {
            val from = ends.first() + outward * DIMENSION_OVERALL_OFFSET_PX
            val to = ends.last() + outward * DIMENSION_OVERALL_OFFSET_PX
            if (distanceToSegmentPx(from, to, tap) <= DIMENSION_TOUCH_SLOP_PX) {
                return MeasureFocus.Dimension(chainIndex, MeasureFocus.Dimension.OVERALL)
            }
        }
    }
    return null
}

private fun hitPlanMeasurement(
    planMeasurements: List<SavedPlanMeasurement>,
    camera: PlanCamera,
    size: IntSize,
    tap: Offset,
): MeasureFocus.Custom? {
    planMeasurements.forEach { saved ->
        val measurement = saved.measurement ?: return@forEach
        val from = camera.toScreen(measurement.from.position, size)
        val to = camera.toScreen(measurement.to.position, size)
        if (distanceToSegmentPx(from, to, tap) <= DIMENSION_TOUCH_SLOP_PX) {
            return MeasureFocus.Custom(saved.id)
        }
    }
    return null
}

private fun distanceToSegmentPx(from: Offset, to: Offset, point: Offset): Float {
    val span = to - from
    val lengthSquared = span.x * span.x + span.y * span.y
    if (lengthSquared < 1e-6f) return hypot(point.x - from.x, point.y - from.y)
    val t = (((point - from).x * span.x + (point - from).y * span.y) / lengthSquared)
        .coerceIn(0f, 1f)
    val nearest = from + span * t
    return hypot(point.x - nearest.x, point.y - nearest.y)
}

/**
 * The end of a plan measurement, drawn as what it is attached to.
 *
 * The shape carries the information: a ring means it is on a corner and will move with
 * it, a bar across means it is on a wall, a plain cross means it is nowhere in particular
 * and is only as good as the finger that placed it. Without this the three are
 * indistinguishable, and a measurement whose end merely *looks* like it is on the corner
 * is exactly the sort of thing that gets trusted and should not be.
 */
private fun DrawScope.drawEndMark(
    centre: Offset,
    kind: SnapKind,
    colour: Color,
    selected: Boolean,
) {
    val radius = if (selected) 8f else 6f
    when (kind) {
        SnapKind.CORNER -> {
            drawCircle(colour, radius = radius, center = centre, style = Stroke(width = 2.5f))
            drawCircle(colour, radius = radius * 0.35f, center = centre)
        }

        SnapKind.WALL -> {
            drawCircle(colour, radius = radius * 0.45f, center = centre)
            drawLine(
                color = colour,
                start = Offset(centre.x - radius, centre.y),
                end = Offset(centre.x + radius, centre.y),
                strokeWidth = 2.5f,
            )
        }

        SnapKind.FREE -> {
            drawLine(colour, Offset(centre.x - radius, centre.y - radius), Offset(centre.x + radius, centre.y + radius), strokeWidth = 2.5f)
            drawLine(colour, Offset(centre.x - radius, centre.y + radius), Offset(centre.x + radius, centre.y - radius), strokeWidth = 2.5f)
        }
    }
}

/** A height, which a floor plan cannot show as a length: a double arrow where it was taken. */
private fun DrawScope.drawHeightMark(centre: Offset, colour: Color, selected: Boolean) {
    val radius = if (selected) 15f else 12f
    drawCircle(colour.copy(alpha = 0.18f), radius = radius, center = centre)
    drawCircle(colour, radius = radius, center = centre, style = Stroke(width = if (selected) 3f else 2f))

    val reach = radius * 0.62f
    val head = radius * 0.32f
    val top = Offset(centre.x, centre.y - reach)
    val bottom = Offset(centre.x, centre.y + reach)
    drawLine(colour, top, bottom, strokeWidth = 2.5f)
    listOf(top to 1f, bottom to -1f).forEach { (tip, sign) ->
        drawLine(colour, tip, Offset(tip.x - head, tip.y + head * sign), strokeWidth = 2.5f)
        drawLine(colour, tip, Offset(tip.x + head, tip.y + head * sign), strokeWidth = 2.5f)
    }
}

/** Plan +y is "away"; screen +y is down. Lengths are preserved, so a unit stays a unit. */
private fun Vec2.toScreenVector() = Offset(x.toFloat(), -y.toFloat())

/** Compose measures arc angles from 3 o'clock, sweeping the way screen y grows. */
private fun screenAngle(vector: Offset): Float =
    Math.toDegrees(atan2(vector.y.toDouble(), vector.x.toDouble())).toFloat()

/** The signed quarter turn from [from] to [to], which are always perpendicular here. */
private fun quarterTurn(from: Offset, to: Offset): Float =
    if (from.x * to.y - from.y * to.x >= 0f) 90f else -90f

private fun Selection.roomId(): Long? = when (this) {
    is Selection.Wall -> roomId
    is Selection.Corner -> roomId
    is Selection.Measurement -> null
    is Selection.Room -> roomId
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
        if (Segments.contains(polygon, point)) return Selection.Room(room.id)
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

/** Which room a point fell inside, for a long press that means "pick this one up". */
private fun findRoom(rooms: List<SavedRoom>, point: Vec2): Long? {
    rooms.forEach { room ->
        val polygon = room.polygonOrNull() ?: return@forEach
        if (Segments.contains(polygon, point)) return room.id
    }
    return null
}

internal fun SavedRoom.polygonOrNull(): Polygon? =
    if (outline.size >= 3) Polygon(outline) else null

/** Fingers are blunt; this is the radius within which a tap counts as "on" something. */
private const val TOUCH_SLOP_PX = 28.0

/** An opening narrower than this on screen has no room for a symbol inside it. */
private const val MINIMUM_SYMBOL_PX = 4f

/** Enough wider than the wall stroke that the gap is a clean break, not a smudge. */
private const val GAP_OVERDRAW_PX = 3f

/** Half the length of the tick drawn across the wall at each side of an opening. */
private const val JAMB_HALF_PX = 5f

/** Half the separation between the two lines of a window's frame. */
private const val GLAZING_HALF_PX = 2.5f

/** How far a dimension line stands off the plan, and its overall line beyond that. */
private const val DIMENSION_RUN_OFFSET_PX = 46f
private const val DIMENSION_OVERALL_OFFSET_PX = 92f

/** A witness line starts just clear of the corner and overruns the last dimension line. */
private const val DIMENSION_WITNESS_GAP_PX = 6f
private const val DIMENSION_WITNESS_OVERRUN_PX = 10f

private const val DIMENSION_TICK_PX = 5f

/** A dimension line is thin; the target around it is not. */
private const val DIMENSION_TOUCH_SLOP_PX = 26f
