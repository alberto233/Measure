package com.measure.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.measure.core.data.SavedMeasurement
import com.measure.core.data.SavedPlanMeasurement
import com.measure.core.data.SavedRoom
import com.measure.core.designsystem.MeasureColours
import com.measure.core.designsystem.MeasureType
import com.measure.core.geometry.Vec2
import com.measure.core.geometry.plan.DimensionChain

/** One piece of text pinned to a point on the plan, in pixels. */
internal data class PlanLabel(
    val text: String,
    val x: Float,
    val y: Float,
    val colour: Color,
    val bold: Boolean = false,
    /**
     * Which label survives when two want the same pixels — higher wins.
     *
     * Default for an ordinary wall length. Raised for anything the user has singled out
     * or measured by hand, because those are the numbers they came to read.
     */
    val priority: Int = 0,
)

/**
 * Text over the plan, drawn as composables positioned at computed offsets.
 *
 * Text on a `Canvas` means either `drawText` with a `TextMeasurer` or a font atlas;
 * placing real `Text` composables is less code, gets font scaling and locale digits for
 * free, and matches how the AR overlay does the same job.
 *
 * **Labels that would land on top of each other are dropped.** A real room has short walls
 * — a 27 cm return beside a doorway, a 38 cm nib — and on a plan zoomed out far enough to
 * see two rooms, three of those in a row put three boxes of text in the same 40 pixels.
 * The result on hardware was `3 5 42 49,6 cm`: four numbers overlapping into one that is
 * not any of them, which is worse than showing none, because it can be misread as a value.
 *
 * Culled here rather than at the point each label is built, because whether two labels
 * collide depends on how wide the text turned out — which is known only after measuring,
 * and only here. The rule the old code used, "skip walls under 25 cm", was the right idea
 * in the wrong unit: it asked about metres in the room when the question is pixels on the
 * screen, so it suppressed nothing at all once the view was zoomed out.
 */
@Composable
internal fun PlanLabels(labels: List<PlanLabel>) {
    if (labels.isEmpty()) return

    Layout(
        content = {
            labels.forEach { label ->
                Text(
                    text = label.text,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MeasureColours.Surface)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    color = label.colour,
                    fontSize = MeasureType.Small.fontSize,
                    fontWeight = if (label.bold) FontWeight.Bold else FontWeight.Medium,
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            val taken = mutableListOf<IntRect>()

            // Highest priority first, so a locked or selected length keeps its place and
            // the ordinary one next to it is the one that goes. Ties fall back to the
            // order the labels were built in, which puts wall lengths above dimensions.
            labels.indices
                .sortedByDescending { labels[it].priority }
                .forEach { index ->
                    val label = labels[index]
                    val placeable = placeables.getOrNull(index) ?: return@forEach
                    val left = (label.x - placeable.width / 2f).toInt()
                    val top = (label.y - placeable.height / 2f).toInt()

                    // A gutter as well as the box itself: two labels that merely touch
                    // still read as one longer number.
                    val claim = IntRect(
                        left = left - LABEL_GUTTER_PX,
                        top = top - LABEL_GUTTER_PX,
                        right = left + placeable.width + LABEL_GUTTER_PX,
                        bottom = top + placeable.height + LABEL_GUTTER_PX,
                    )
                    if (taken.any { it.overlaps(claim) }) return@forEach

                    taken += claim
                    placeable.place(left, top)
                }
        }
    }
}

/**
 * Wall lengths.
 *
 * A locked wall is marked, because the distinction matters: an unlocked length is what
 * the camera estimated, a locked one is what the user measured with a tape and is
 * therefore the number the rest of the plan was fitted around.
 */
internal fun wallLabels(
    rooms: List<SavedRoom>,
    camera: PlanCamera,
    size: IntSize,
    dragging: EditorViewModel.DragState?,
    selection: Selection,
    formatLength: (Double) -> String,
): List<PlanLabel> = buildList {
    if (size == IntSize.Zero) return@buildList

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

            val locked = room.lockedLengths.containsKey(index)
            val selected = selection == Selection.Wall(room.id, index)
            val screen = camera.toScreen((from + to) * 0.5, size)
            add(
                PlanLabel(
                    text = if (locked) "${formatLength(length)} ✓" else formatLength(length),
                    x = screen.x,
                    y = screen.y,
                    colour = when {
                        selected -> MeasureColours.Sampling
                        locked -> MeasureColours.Ready
                        else -> MeasureColours.Ink
                    },
                    bold = locked,
                    priority = when {
                        selected -> SELECTED_PRIORITY
                        locked -> LOCKED_PRIORITY
                        else -> 0
                    },
                ),
            )
        }
    }
}

/**
 * Standalone measurements, labelled with the value.
 *
 * Unlabelled they are unreadable: a plumb measurement projects onto the floor as a single
 * point, so a plan holding two room heights showed two bare dots and nothing else. The
 * number is the entire content of a measurement, and it belongs on the plan next to it.
 */
internal fun measurementLabels(
    measurements: List<SavedMeasurement>,
    camera: PlanCamera,
    size: IntSize,
    selection: Selection,
    formatLength: (Double) -> String,
): List<PlanLabel> = buildList {
    if (size == IntSize.Zero) return@buildList

    measurements.forEach { measurement ->
        val from = camera.toScreen(measurement.from.toFloorPlane(), size)
        val to = camera.toScreen(measurement.to.toFloorPlane(), size)
        val vertical = measurement.isVerticalOnPlan
        val selected = selection == Selection.Measurement(measurement.id)

        add(
            PlanLabel(
                // The arrow says "this is a height", which the plan itself cannot: a
                // vertical measurement has no extent on a floor plan to show it with.
                text = if (vertical) {
                    "↕ ${formatLength(measurement.length.metres)}"
                } else {
                    formatLength(measurement.length.metres)
                },
                x = (from.x + to.x) / 2f,
                // Lifted clear of the symbol it belongs to rather than sitting on it.
                y = (from.y + to.y) / 2f - if (vertical) VERTICAL_LABEL_LIFT_PX else 0f,
                colour = if (selected) MeasureColours.Sampling else MeasureColours.InkMuted,
                bold = selected,
                priority = if (selected) SELECTED_PRIORITY else MEASUREMENT_PRIORITY,
            ),
        )
    }
}

/**
 * The one distance being read, if it is a drawn one.
 *
 * Marked with a tilde. These are consequences of the plan rather than observations of the
 * room, and the difference has to be visible on the drawing itself and not only in a
 * panel the user may never open — a number that looks exactly like a measured one will be
 * treated as one.
 */
internal fun planMeasurementLabels(
    planMeasurements: List<SavedPlanMeasurement>,
    camera: PlanCamera,
    size: IntSize,
    measuring: Boolean,
    focus: MeasureFocus,
    formatLength: (Double) -> String,
): List<PlanLabel> = buildList {
    if (size == IntSize.Zero || !measuring) return@buildList
    val active = focus as? MeasureFocus.Custom ?: return@buildList

    val saved = planMeasurements.firstOrNull { it.id == active.id } ?: return@buildList
    val measurement = saved.measurement ?: return@buildList
    val screen = camera.toScreen((measurement.from.position + measurement.to.position) * 0.5, size)

    add(
        PlanLabel(
            text = "~ ${formatLength(measurement.length)}",
            x = screen.x,
            y = screen.y - PLAN_MEASUREMENT_LABEL_LIFT_PX,
            colour = MeasureColours.Sampling,
            bold = true,
            // This is the one thing the measure view was opened to read. It cannot lose.
            priority = SELECTED_PRIORITY,
        ),
    )
}

/** Clear of the line itself, which the label would otherwise sit exactly on top of. */
private const val PLAN_MEASUREMENT_LABEL_LIFT_PX = 16f

/**
 * The number on the one dimension run being read.
 *
 * Nothing is labelled until it is asked for. A plan carrying every dimension it could is
 * a drawing nobody reads — the numbers collide with each other and with the geometry, and
 * the one being looked for is buried among a dozen that are not. The lines and ticks stay
 * drawn, because they cost nothing to look past and they are what there is to aim at.
 */
internal fun dimensionLabels(
    chains: List<DimensionChain>,
    camera: PlanCamera,
    size: IntSize,
    focus: MeasureFocus,
    formatLength: (Double) -> String,
): List<PlanLabel> = buildList {
    if (size == IntSize.Zero) return@buildList
    val target = focus as? MeasureFocus.Dimension ?: return@buildList
    val chain = chains.getOrNull(target.chain) ?: return@buildList
    if (chain.ticks.size < 2) return@buildList

    val outwardX = -chain.normal.x.toFloat()
    val outwardY = chain.normal.y.toFloat()

    val (along, length, offset) = if (target.isOverall) {
        Triple(
            (chain.ticks.first() + chain.ticks.last()) / 2.0,
            chain.overall,
            DIMENSION_OVERALL_LABEL_OFFSET_PX,
        )
    } else {
        val segment = chain.segments.getOrNull(target.segment) ?: return@buildList
        Triple(segment.midpoint, segment.length, DIMENSION_RUN_LABEL_OFFSET_PX)
    }

    val anchor = camera.toScreen(chain.pointAt(along), size)
    add(
        PlanLabel(
            text = formatLength(length),
            x = anchor.x + outwardX * offset,
            y = anchor.y + outwardY * offset,
            colour = MeasureColours.Sampling,
            bold = true,
            // The dimension the user tapped. It outranks the wall lengths it sits among.
            priority = SELECTED_PRIORITY,
        ),
    )
}

/** Matched to the dimension lines in PlanCanvas, which these sit on. */
private const val DIMENSION_RUN_LABEL_OFFSET_PX = 46f
private const val DIMENSION_OVERALL_LABEL_OFFSET_PX = 92f

/**
 * Walls shorter than this get no label at all.
 *
 * A backstop rather than the collision rule — that lives in [PlanLabels], where widths are
 * known. This only removes the cases where the wall is a construction detail rather than
 * something anyone would measure.
 */
private const val MINIMUM_LABELLED_METRES = 0.15

/** Two labels this close read as one number, so treat touching as overlapping. */
private const val LABEL_GUTTER_PX = 3

/** The one the user is looking at. Nothing displaces it. */
private const val SELECTED_PRIORITY = 30

/** A length the user measured with a tape, which outranks one the camera estimated. */
private const val LOCKED_PRIORITY = 20

/** A distance the user drew, which they drew because they wanted to see it. */
private const val MEASUREMENT_PRIORITY = 10

/** Enough to clear the height symbol, whose radius is 15 px at its largest. */
private const val VERTICAL_LABEL_LIFT_PX = 26f
