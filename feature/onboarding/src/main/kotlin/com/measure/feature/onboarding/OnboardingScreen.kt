package com.measure.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.measure.core.designsystem.MeasureButton
import com.measure.core.designsystem.MeasureCard
import com.measure.core.designsystem.MeasureColours
import com.measure.core.designsystem.MeasurePrimaryButton
import com.measure.core.designsystem.MeasureRule
import com.measure.core.designsystem.MeasureShape
import com.measure.core.designsystem.MeasureSpace
import com.measure.core.designsystem.MeasureTag
import com.measure.core.designsystem.MeasureType

/**
 * What to expect, and the four things that decide whether the first scan is any good.
 *
 * This screen exists to answer a risk rather than to decorate a launch: *"users expect
 * LiDAR precision and leave one-star reviews"* (docs/PRODUCT_PLAN.md §7, and the reason
 * §6 now optimises for rating above all else). The competitors' review pages are full of
 * people who stood in a doorway, pointed across a dark room, got a bad number and
 * concluded the app was broken. They were not wrong about the number. They were never
 * told what the thing needs.
 *
 * Two decisions worth defending:
 *
 * **It states the accuracy up front, in centimetres.** Naming ±2–3 cm before anyone
 * measures anything sounds like an odd way to sell a measuring app, and it is the whole
 * strategy: a user told to expect a couple of centimetres and getting three is satisfied,
 * and the same user expecting a laser and getting three is writing a review. This is the
 * same commitment `ACCURACY.md` makes to displaying uncertainty on every reading, applied
 * before the first one.
 *
 * **One scrolling page, not a carousel of steps.** It is also the reference someone opens
 * later from the home screen when a capture went badly, and paging through four screens to
 * re-read one line is hostile to that. It also removes the temptation to pad thin content
 * out to fill a step.
 *
 * The guidance itself is the user-facing half of `docs/ACCURACY.md` §1–2: range gating
 * (M4), tracking-quality gating (M5), and the semantic error that no amount of maths can
 * fix — tapping the skirting board instead of the joint.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    firstRun: Boolean = true,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(MeasureColours.Surface)
            .safeDrawingPadding()
            .padding(horizontal = MeasureSpace.Base),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MeasureSpace.Base),
        ) {
            Spacer(Modifier.height(MeasureSpace.Tight))
            Header(firstRun)
            Expectation()

            for (point in GUIDANCE) {
                Guidance(point)
            }

            // Sits after the guidance rather than beside the accuracy figure, where it
            // would read as an apology for it.
            Text(
                text = "Every measurement is shown with its own tolerance, so you can " +
                    "always see how much to trust it.",
                color = MeasureColours.InkMuted,
                style = MeasureType.Small,
            )
            Spacer(Modifier.height(MeasureSpace.Tight))
        }

        MeasureRule()
        Column(
            Modifier.padding(vertical = MeasureSpace.Base),
            verticalArrangement = Arrangement.spacedBy(MeasureSpace.Tight),
        ) {
            MeasurePrimaryButton(
                label = if (firstRun) "Start measuring" else "Done",
                onClick = onDone,
            )
        }
    }
}

@Composable
private fun Header(firstRun: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(MeasureSpace.Hair)) {
        MeasureTag(if (firstRun) "welcome" else "guide")
        Text(
            text = "Measuring well",
            color = MeasureColours.Ink,
            style = MeasureType.Display,
        )
    }
}

/**
 * The number, stated plainly and early.
 *
 * Given the accent wash rather than buried in body text because it is the single most
 * important sentence on the screen, and the one a user has to have read for the rest of
 * the app to be judged fairly.
 */
@Composable
private fun Expectation() {
    Column(
        Modifier
            .fillMaxWidth()
            // Matches the cards below. Square against four rounded panels reads as an
            // unstyled block rather than as emphasis.
            .clip(RoundedCornerShape(MeasureShape.Panel))
            .background(MeasureColours.AccentWash)
            .padding(MeasureSpace.Snug),
        verticalArrangement = Arrangement.spacedBy(MeasureSpace.Hair),
    ) {
        Text(
            text = "Expect about ±2–3 cm",
            color = MeasureColours.Ink,
            style = MeasureType.Title,
        )
        Text(
            text = "Your phone measures with its camera, not a laser. Done well that is " +
                "accurate enough to order flooring or check a sofa fits — and it is not " +
                "a tape measure. For anything that has to be exact, check it with one.",
            color = MeasureColours.InkMuted,
            style = MeasureType.Body,
        )
    }
}

@Composable
private fun Guidance(point: GuidancePoint) {
    MeasureCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MeasureSpace.Snug),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = point.ordinal,
                color = MeasureColours.Accent,
                style = MeasureType.Value.copy(fontSize = MeasureType.Title.fontSize),
                modifier = Modifier.width(22.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(MeasureSpace.Hair)) {
                Text(point.title, color = MeasureColours.Ink, style = MeasureType.Title)
                Text(point.detail, color = MeasureColours.InkMuted, style = MeasureType.Body)
            }
        }
    }
}

private data class GuidancePoint(
    val ordinal: String,
    val title: String,
    val detail: String,
)

/**
 * Four points, in the order they bite.
 *
 * Each maps to a mitigation in `docs/ACCURACY.md` that only works if the user cooperates:
 * the app can refuse a bad point, but it cannot walk the room for them. Deliberately not
 * longer — a tutorial nobody finishes teaches nothing, and these four cover the failures
 * that actually appear in competitors' one-star reviews.
 */
private val GUIDANCE = listOf(
    GuidancePoint(
        ordinal = "01",
        title = "Walk the room",
        detail = "Stand 1–3 m from each corner and move round to the next one. " +
            "Measuring the far wall from the doorway is the single most common way to " +
            "get a bad plan — accuracy falls off with distance.",
    ),
    GuidancePoint(
        ordinal = "02",
        title = "Give it light and detail",
        detail = "The camera needs to see texture to know where it is. A dim room, blank " +
            "white walls or a glossy floor all make tracking drift. Put the lights on.",
    ),
    GuidancePoint(
        ordinal = "03",
        title = "Move slowly",
        detail = "Sweep the phone gently and pause before each tap. Quick movements blur " +
            "the frames the measurement is built from, and the app will tell you when it " +
            "has lost confidence.",
    ),
    GuidancePoint(
        ordinal = "04",
        title = "Aim where the wall meets the floor",
        detail = "Put the reticle in the joint itself, not on the skirting board above " +
            "it. A skirting board is a couple of centimetres proud of the wall, and that " +
            "error goes straight into the plan.",
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
