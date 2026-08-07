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
 * somewhere deliberate.
 *
 * **Open is the size of its content; closed is the handle alone.** There is no middle
 * "peek" any more. A peek was a compromise between showing the panel and showing the
 * drawing and it did neither — it cropped the panel mid-sentence *and* still covered a
 * fifth of the plan. Pushed down, the sheet leaves nothing but a grip on the bottom edge,
 * which is what someone looking at a floor plan wants; anything that changes what the panel
 * is *for* — a new section, a new selection — brings it back at whatever size the new
 * content needs.
 *
 * @param expanded hoisted, because the editor reopens the sheet when the section or the
 *   selection changes. A panel full of text fields under a keyboard needs the room, and
 *   asking the user to drag for it every time would be the same fault in a politer form.
 */
@Composable
fun MeasureSheet(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
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

        // The header's height is a **constant**, not a measurement, and that is a fix
        // rather than a simplification.
        //
        // It used to be measured with `onSizeChanged`, like the content. But the header is
        // a direct child of this Column, which has an explicit height — so measuring it
        // there measured what the sheet was *giving* it, not what it wanted. Collapsing
        // then fed back on itself: the sheet shrank towards the header's height, which
        // squeezed the header, which lowered the target, which shrank the sheet. It
        // converged on one pixel over a second or so — the sheet visibly dissolving into
        // the bottom edge and leaving nothing to grab.
        //
        // The content escapes this because it sits inside a `verticalScroll`, which
        // measures its child with unbounded height. The header does not, so it gets a
        // number that cannot depend on the answer it is used to compute.
        val headerPx = with(density) { (HandleRowHeight + HairlineHeight).toPx() }
        val wantedPx = contentPx + headerPx

        val ceilingPx = with(density) { (available * expandedFraction).toPx() }

        // Open is what the content needs, capped so the sheet can never take the whole
        // screen. Closed is the header alone — the rule and the handle — so pushing it down
        // leaves a grip on the bottom edge and gives the drawing everything else.
        // Never below the header: a sheet shorter than its own grip cannot be reopened.
        val expandedPx = wantedPx.coerceIn(headerPx, maxOf(ceilingPx, headerPx))
        val peekPx = headerPx

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
                    .height(HandleRowHeight)
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
                    .clickable { onExpandedChange(!expanded) },
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

/** Defaults a caller may need to reason about without measuring the sheet. */
object MeasureSheetDefaults {
    /**
     * How much of the canvas to keep clear when fitting a drawing behind the sheet.
     *
     * Not a height the sheet ever takes — it sizes itself to its content. This is the
     * editor's allowance for "the sheet will be about this tall", used once, when the plan
     * is first fitted. Measuring the live sheet instead is a race: it reopens whenever the
     * selection changes, so a fit during that animation reads a height the sheet is only
     * passing through, and the drawing ends up squashed into a sliver at the top.
     */
    val PlanClearance: Dp = 180.dp
}

/** Expanded, but never the whole screen: the plan has to stay visible behind it. */
private const val ExpandedFraction = 0.62f

private val HandleWidth = 36.dp
private val HandleHeight = 4.dp

/**
 * The grip row, at a fixed height so the collapsed size cannot depend on the measurement
 * it determines. Comfortably over the 48dp touch minimum once the surrounding sheet edge
 * is included, and the whole row is clickable rather than just the bar inside it.
 */
private val HandleRowHeight = 28.dp

/** [MeasureRule]'s thickness, counted so the collapsed sheet clears its own top edge. */
private val HairlineHeight = 1.dp
