package com.traza.feature.onboarding

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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.traza.core.designsystem.MeasureButton
import com.traza.core.designsystem.MeasureColours
import com.traza.core.designsystem.MeasurePrimaryButton
import com.traza.core.designsystem.MeasureShape
import com.traza.core.designsystem.MeasureSpace
import com.traza.core.designsystem.MeasureTag
import com.traza.core.designsystem.MeasureType
import com.traza.core.designsystem.touchTarget
import kotlinx.coroutines.launch

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
 * **The deck is a pager, and every control sits at the bottom.** Both halves of that come
 * from the same observation: this screen is held one-handed by somebody who has just
 * installed the app, and a card deck that only moves when you reach the top of the screen is
 * a card deck fighting the thumb. Swipe is the gesture every onboarding deck on the phone
 * already uses, so it costs nothing to learn; Next is kept beside it for people who do not
 * swipe, and Back is gone because the swipe replaces it and a third button in that row would
 * be the crowd rather than the affordance.
 *
 * **Skip is tertiary and on the left**, opposite the primary and styled as text rather than
 * as a button. It has to be reachable — a tutorial nobody can escape earns its own one-star
 * reviews, and the guidance is one tap away from the home screen afterwards — without
 * competing with the action that moves the deck forward. It leaves on the last card, where
 * the primary already ends the deck and two controls doing the same thing is a choice nobody
 * needs to make.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    firstRun: Boolean = true,
) {
    val pager = rememberPagerState(pageCount = { STEPS.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == STEPS.lastIndex

    Column(
        modifier
            .fillMaxSize()
            .background(MeasureColours.Surface)
            .safeDrawingPadding()
            .padding(horizontal = MeasureSpace.Base),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = MeasureSpace.Tight)) {
            MeasureTag(
                stringResource(
                    if (firstRun) R.string.onboarding_tag_welcome else R.string.onboarding_tag_guide,
                ),
            )
        }

        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f),
        ) { page ->
            StepCard(STEPS[page])
        }

        Dots(count = STEPS.size, current = pager.currentPage)

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = MeasureSpace.Base, bottom = MeasureSpace.Base),
            horizontalArrangement = Arrangement.spacedBy(MeasureSpace.Snug),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!last) {
                Text(
                    text = stringResource(R.string.onboarding_skip),
                    color = MeasureColours.InkMuted,
                    style = MeasureType.Label,
                    modifier = Modifier
                        .clip(RoundedCornerShape(MeasureShape.Edge))
                        .touchTarget()
                        .clickable(onClick = onDone)
                        .padding(horizontal = MeasureSpace.Snug),
                )
            }
            MeasurePrimaryButton(
                label = stringResource(
                    when {
                        last && firstRun -> R.string.onboarding_start
                        last -> R.string.onboarding_done
                        else -> R.string.onboarding_next
                    },
                ),
                onClick = {
                    if (last) {
                        onDone()
                    } else {
                        scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StepCard(step: GuidanceStep) {
    Column(
        // Centred in the pager, then nudged up by roughly the height of the dots. Optically
        // centred on the *screen* rather than in the box it is given, which are no longer the
        // same thing now that every control lives at the bottom.
        Modifier.fillMaxSize().padding(bottom = MeasureSpace.Wide),
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
            text = stringResource(step.title),
            color = MeasureColours.Ink,
            style = MeasureType.Display,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(MeasureSpace.Snug))
        Text(
            text = stringResource(step.body),
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
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
    val art: StepArt,
)

/**
 * Five cards, accuracy first and then the four things that decide a capture.
 *
 * Each of the four maps to a mitigation in `docs/ACCURACY.md` that only works if the user
 * cooperates: the app can refuse a bad point, but it cannot walk the room for them.
 * Deliberately not longer — a deck nobody finishes teaches nothing, and these cover the
 * failures that actually appear in competitors' one-star reviews.
 *
 * The prose lives in `res/values/strings.xml`, and the order lives here. Which card comes
 * first is a product decision — the accuracy figure leads, and a translator moving it would
 * be moving the mitigation — so it is not something a string file gets to express.
 */
private val STEPS = listOf(
    GuidanceStep(
        title = R.string.onboarding_tolerance_title,
        body = R.string.onboarding_tolerance_body,
        art = StepArt.TOLERANCE,
    ),
    GuidanceStep(
        title = R.string.onboarding_walk_title,
        body = R.string.onboarding_walk_body,
        art = StepArt.WALK,
    ),
    GuidanceStep(
        title = R.string.onboarding_light_title,
        body = R.string.onboarding_light_body,
        art = StepArt.LIGHT,
    ),
    GuidanceStep(
        title = R.string.onboarding_slow_title,
        body = R.string.onboarding_slow_body,
        art = StepArt.SLOW,
    ),
    GuidanceStep(
        title = R.string.onboarding_joint_title,
        body = R.string.onboarding_joint_body,
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
    MeasureButton(
        label = stringResource(R.string.onboarding_open),
        onClick = onOpen,
        modifier = modifier,
    )
}
