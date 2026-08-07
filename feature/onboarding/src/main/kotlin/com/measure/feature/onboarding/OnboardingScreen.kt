package com.measure.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.measure.core.designsystem.MeasureButton
import com.measure.core.designsystem.MeasureColours
import com.measure.core.designsystem.MeasurePrimaryButton
import com.measure.core.designsystem.MeasureShape
import com.measure.core.designsystem.MeasureSpace
import com.measure.core.designsystem.MeasureTag
import com.measure.core.designsystem.MeasureType
import com.measure.core.designsystem.touchTarget

/**
 * What to expect, then the four things that decide whether the first scan is any good.
 *
 * This screen answers a risk rather than decorating a launch: *"users expect LiDAR
 * precision and leave one-star reviews"* (docs/PRODUCT_PLAN.md §7, and the reason §6 now
 * optimises for rating above all else). The competitors' review pages are full of people
 * who stood in a doorway, pointed across a dark room, got a bad number and concluded the
 * app was broken. They were not wrong about the number. Nobody had told them what the
 * thing needs.
 *
 * **One card at a time, and skippable from the first.** The earlier version was a single
 * scrolling page, on the reasoning that it doubles as reference material. A field test
 * disagreed: the content is four short pieces of advice, which is exactly the shape a card
 * deck fits, and a wall of prose in front of somebody who has just installed a measuring
 * app is a wall of prose they scroll past. Steps also let each point carry a drawing, and
 * the drawings do more of the work than the sentences do.
 *
 * **Accuracy is the first card and cannot be skipped past accidentally**, in the sense that
 * it is the one card everybody sees even if they hit Skip on it — they have already read it
 * by then. Naming ±2–3 cm before anyone measures anything sounds like an odd way to sell a
 * measuring app, and it is the whole strategy: a user told to expect a couple of
 * centimetres and getting three is satisfied, and the same user expecting a laser is
 * writing a review.
 *
 * Skip is present on every card and does exactly what it says. A tutorial nobody can escape
 * earns its own one-star reviews, and the guidance is one tap away from the home screen
 * afterwards.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    firstRun: Boolean = true,
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = STEPS[index]
    val last = index == STEPS.lastIndex

    Column(
        modifier
            .fillMaxSize()
            .background(MeasureColours.Surface)
            .safeDrawingPadding()
            .padding(horizontal = MeasureSpace.Base),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = MeasureSpace.Tight),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MeasureTag(if (firstRun) "welcome" else "guide")
            if (!last) {
                Text(
                    text = "Skip",
                    color = MeasureColours.InkMuted,
                    style = MeasureType.Label,
                    modifier = Modifier
                        .clip(RoundedCornerShape(MeasureShape.Edge))
                        .touchTarget()
                        .clickable(onClick = onDone)
                        .padding(horizontal = MeasureSpace.Tight),
                )
            }
        }

        // Crossfade only. A horizontal slide would imply the cards can be swiped, and
        // they cannot — one promise the interface should not make and then break.
        AnimatedContent(
            targetState = index,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(160)) },
            label = "guidance step",
            modifier = Modifier.weight(1f),
        ) { current ->
            StepCard(STEPS[current])
        }

        Dots(count = STEPS.size, current = index)

        Column(
            Modifier.padding(top = MeasureSpace.Base, bottom = MeasureSpace.Base),
            verticalArrangement = Arrangement.spacedBy(MeasureSpace.Tight),
        ) {
            MeasurePrimaryButton(
                label = when {
                    last && firstRun -> "Start measuring"
                    last -> "Done"
                    else -> "Next"
                },
                onClick = { if (last) onDone() else index++ },
            )
            if (index > 0) {
                MeasureButton(
                    label = "Back",
                    onClick = { index-- },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun StepCard(step: GuidanceStep) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StepIllustration(
            art = step.art,
            modifier = Modifier
                .fillMaxWidth()
                .height(196.dp)
                .padding(horizontal = MeasureSpace.Base),
        )
        Spacer(Modifier.height(MeasureSpace.Wide))
        Text(
            text = step.title,
            color = MeasureColours.Ink,
            style = MeasureType.Display,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(MeasureSpace.Snug))
        Text(
            text = step.body,
            color = MeasureColours.InkMuted,
            style = MeasureType.Body,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = MeasureSpace.Tight),
        )
    }
}

/** Where you are in the deck. Four dots and a fifth is a count nobody has to be told. */
@Composable
private fun Dots(count: Int, current: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { position ->
            Box(
                Modifier
                    .padding(horizontal = MeasureSpace.Hair)
                    .size(if (position == current) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (position == current) MeasureColours.Accent else MeasureColours.Line,
                    ),
            )
        }
    }
}

internal data class GuidanceStep(
    val title: String,
    val body: String,
    val art: StepArt,
)

/**
 * Five cards, accuracy first and then the four things that decide a capture.
 *
 * Each of the four maps to a mitigation in `docs/ACCURACY.md` that only works if the user
 * cooperates: the app can refuse a bad point, but it cannot walk the room for them.
 * Deliberately not longer — a deck nobody finishes teaches nothing, and these cover the
 * failures that actually appear in competitors' one-star reviews.
 */
private val STEPS = listOf(
    GuidanceStep(
        title = "Expect about ±2–3 cm",
        body = "Your phone measures with its camera, not a laser. Done well that is " +
            "accurate enough to order flooring or check a sofa fits. Every measurement " +
            "is shown with its own tolerance, so you can always see how much to trust " +
            "it — and for anything that has to be exact, check it with a tape.",
        art = StepArt.TOLERANCE,
    ),
    GuidanceStep(
        title = "Walk the room",
        body = "Stand 1–3 m from each corner and move round to the next one. Measuring " +
            "the far wall from the doorway is the single most common way to get a bad " +
            "plan — accuracy falls off with distance.",
        art = StepArt.WALK,
    ),
    GuidanceStep(
        title = "Give it light and detail",
        body = "The camera needs to see texture to know where it is. A dim room, blank " +
            "white walls or a glossy floor all make tracking drift. Put the lights on.",
        art = StepArt.LIGHT,
    ),
    GuidanceStep(
        title = "Move slowly",
        body = "Sweep the phone gently and pause before each tap. Quick movements blur " +
            "the frames the measurement is built from, and the app will tell you when " +
            "it has lost confidence.",
        art = StepArt.SLOW,
    ),
    GuidanceStep(
        title = "Aim where the wall meets the floor",
        body = "Put the reticle in the joint itself, not on the skirting board above it. " +
            "A skirting board is a couple of centimetres proud of the wall, and that " +
            "error goes straight into the plan.",
        art = StepArt.JOINT,
    ),
)

/**
 * The way back to this screen once it has been dismissed.
 *
 * Its own composable so the home screen does not have to know how the guidance is
 * labelled. A tutorial that can only ever be seen once is useless precisely when it is
 * wanted — after a capture has gone badly, which is the moment someone wants to know what
 * they did wrong.
 */
@Composable
fun GuidanceButton(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    MeasureButton(label = "How to measure", onClick = onOpen, modifier = modifier)
}
