package com.measure.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The bottom sheet the editor's panel became — docs/PRODUCT_PLAN.md M10a.
 *
 * It replaces a panel with a fixed maximum height that scrolled inside itself, and the cap
 * was not a detail: a door added to a wall that already had two landed below the fold, the
 * user could not see the row confirming it, assumed the button had missed, and added the
 * same door four times. Scrolling the new row into view fixed the symptom. Being able to
 * make the panel *bigger* fixes the cause.
 *
 * Two states rather than free height. A sheet that stops wherever the finger left it looks
 * broken half the time and has to be nudged into place; two anchors mean every release ends
 * somewhere deliberate. Peeked shows the top of the panel and the plan behind it, which is
 * the editor's constraint — chrome that grows eats the drawing the user came to look at.
 *
 * @param expanded hoisted, because the editor expands the sheet when something is selected.
 *   A panel full of text fields under a keyboard needs the room, and asking the user to drag
 *   for it every time would be the same fault in a politer form.
 */
@Composable
fun MeasureSheet(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    peekHeight: Dp = PeekHeight,
    expandedFraction: Float = ExpandedFraction,
    /**
     * Hoisted so a caller can scroll its own content. The editor uses it to bring the row
     * confirming a newly added door into view, which is half of the fix for the door that
     * got added four times — the other half is being able to make the sheet taller at all.
     */
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val available = maxHeight

        // What the content actually needs, measured rather than assumed.
        //
        // Without this the sheet was always the same height whatever was in it: a wall
        // panel and a two-line readout both opened at 62% of the screen, and the readout
        // spent most of that on white space while covering the drawing the user was
        // reading it about. A sheet that is bigger than its contents is not a neutral
        // choice on this screen — the plan is what it is hiding.
        var contentPx by remember { mutableIntStateOf(0) }
        var headerPx by remember { mutableIntStateOf(0) }
        val wantedPx = (contentPx + headerPx).toFloat()

        val ceilingPx = with(density) { (available * expandedFraction).toPx() }
        val floorPx = with(density) { peekHeight.coerceAtMost(available).toPx() }

        // Both anchors are capped by what the content needs. Short content collapses the
        // two onto each other, which is correct: there is nothing to expand *to*, so the
        // sheet stops being draggable rather than offering a gesture that reveals nothing.
        val expandedPx = wantedPx.coerceIn(MINIMUM_PX, ceilingPx)
        val peekPx = minOf(floorPx, expandedPx)

        val height = remember { Animatable(if (expanded) expandedPx else peekPx) }
        val scope = rememberCoroutineScope()

        // Follows the hoisted state, including when the editor changes it rather than the
        // finger. Keyed on the anchors too, so a rotation or a font-scale change re-settles
        // the sheet onto the new ones instead of leaving it at a stale pixel height.
        LaunchedEffect(expanded, peekPx, expandedPx) {
            height.animateTo(if (expanded) expandedPx else peekPx)
        }

        val drag = rememberDraggableState { delta ->
            // Up is negative in screen coordinates and up makes the sheet taller.
            scope.launch { height.snapTo((height.value - delta).coerceIn(peekPx, expandedPx)) }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(density) { height.value.toDp() })
                .clip(RoundedCornerShape(topStart = MeasureShape.Panel, topEnd = MeasureShape.Panel))
                // Opaque rather than the camera scrim. There is no camera behind the editor,
                // and a translucent panel let the plan's own lines bleed through the text.
                .background(MeasureColours.Panel),
        ) {
            // A genuine top edge.
            //
            // Panel and Surface are the same white in this direction, so without a hairline
            // the sheet has no boundary at all against the drawing behind it — which is
            // precisely what "the cards don't work" was about.
            MeasureRule()

            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { headerPx = it.height }
                    .draggable(
                        state = drag,
                        orientation = Orientation.Vertical,
                        // Decided by where it ended up, not by how fast it was moving. A
                        // velocity rule makes a slow, deliberate drag to just past halfway
                        // spring back, which reads as the sheet refusing an instruction.
                        //
                        // Settled here rather than left to the effect above: a drag that
                        // ends on the side it started from does not change `expanded`, so
                        // the effect would not re-run and the sheet would stay stuck at
                        // whatever height the finger left it.
                        onDragStopped = {
                            val toExpanded = height.value > (peekPx + expandedPx) / 2f
                            height.animateTo(if (toExpanded) expandedPx else peekPx)
                            onExpandedChange(toExpanded)
                        },
                    )
                    // Tappable as well as draggable. The handle is the only affordance and a
                    // tap is what people try first.
                    .clickable { onExpandedChange(!expanded) }
                    .padding(vertical = MeasureSpace.Snug),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(HandleWidth)
                        .height(HandleHeight)
                        .clip(RoundedCornerShape(MeasureShape.Pill))
                        .background(MeasureColours.Line),
                )
            }

            Column(Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                // Nested so its natural height can be measured. Inside a vertical scroll
                // the child is given unbounded height, so this reports what the content
                // wants rather than what the sheet is currently giving it — which is the
                // number the anchors above are derived from.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { contentPx = it.height }
                        .padding(
                            start = MeasureSpace.Base,
                            end = MeasureSpace.Base,
                            bottom = MeasureSpace.Base,
                        ),
                    verticalArrangement = Arrangement.spacedBy(MeasureSpace.Snug),
                    content = content,
                )
            }
        }
    }
}

/**
 * Defaults a caller may need to reason about without measuring the sheet.
 *
 * [PeekHeight] is public because the editor fits its plan to the space the sheet leaves,
 * and measuring the live sheet to find that out is a race: the sheet expands the moment
 * something is selected, so a fit that happens during the animation reads a height the
 * sheet is only passing through and squashes the drawing into a sliver.
 */
object MeasureSheetDefaults {
    /** Enough for a heading and one row of controls, which is what a peeked panel is for. */
    val PeekHeight: Dp = 132.dp
}

private val PeekHeight = MeasureSheetDefaults.PeekHeight

/** Expanded, but never the whole screen: the plan has to stay visible behind it. */
private const val ExpandedFraction = 0.62f

private val HandleWidth = 36.dp
private val HandleHeight = 4.dp

/** Enough for the handle and one line, before anything has been measured. */
private const val MINIMUM_PX = 1f
