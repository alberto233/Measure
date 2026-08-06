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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

        // Never taller than there is room for, and never shorter than the peek — on a small
        // screen or with a large system font the two can meet, and the sheet then simply
        // stops being draggable rather than inverting.
        val peekPx = with(density) { peekHeight.coerceAtMost(available).toPx() }
        val expandedPx = with(density) { (available * expandedFraction).toPx() }
            .coerceAtLeast(peekPx)

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
            Box(
                Modifier
                    .fillMaxWidth()
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

            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(start = MeasureSpace.Base, end = MeasureSpace.Base, bottom = MeasureSpace.Base),
                verticalArrangement = Arrangement.spacedBy(MeasureSpace.Snug),
                content = content,
            )
        }
    }
}

/** Enough for a heading and one row of controls, which is what a peeked panel is for. */
private val PeekHeight = 132.dp

/** Expanded, but never the whole screen: the plan has to stay visible behind it. */
private const val ExpandedFraction = 0.62f

private val HandleWidth = 36.dp
private val HandleHeight = 4.dp
