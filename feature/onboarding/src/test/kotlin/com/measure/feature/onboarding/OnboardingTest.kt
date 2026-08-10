package com.measure.feature.onboarding

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The guidance screen, and the flag that decides whether anyone sees it.
 *
 * Both halves matter and they fail differently. A screen that renders but whose flag never
 * sticks shows the tutorial on every launch, which is the most irritating bug this feature
 * could ship. A flag that sticks but a screen that does not render leaves a first run
 * staring at nothing.
 */
@RunWith(RobolectricTestRunner::class)
class OnboardingTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun clearStoredFlag() {
        // Robolectric keeps SharedPreferences for the life of the process, so without
        // this the second test to run would inherit the first one's flag.
        context.getSharedPreferences("onboarding", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `a fresh install has not seen the guidance`() {
        assertFalse(OnboardingStore(context).hasSeenGuidance)
    }

    @Test
    fun `the flag survives a new store, because it is what stops the tutorial repeating`() {
        OnboardingStore(context).markGuidanceSeen()

        // A second instance, as the next launch would build. Reading back through the
        // same object would pass even if nothing were ever written to disk.
        assertTrue(OnboardingStore(context).hasSeenGuidance)
    }

    /**
     * The accuracy figure is the first card, not merely present somewhere in the deck.
     *
     * The one sentence this screen exists to deliver, and the only one a user who skips
     * immediately is guaranteed to have read. If it ever moves down the deck the mitigation
     * for "users expect LiDAR precision" is gone while the screen still looks fine.
     */
    @Test
    fun `the accuracy figure is stated before anything else`() {
        compose.setContent { OnboardingScreen(onDone = {}) }

        compose.onNodeWithText("Expect about ±2–3 cm").assertIsDisplayed()
    }

    /** Every card is reachable by pressing Next, and in the order the deck declares. */
    @Test
    fun `every guidance point can be reached`() {
        compose.setContent { OnboardingScreen(onDone = {}) }

        for (title in listOf(
            "Walk the room",
            "Give it light and detail",
            "Move slowly",
            "Aim where the wall meets the floor",
        )) {
            compose.onNodeWithText("Next").performClick()
            compose.onNodeWithText(title).assertIsDisplayed()
        }
    }

    /** The deck is a pager, so a swipe is the first thing anyone will try. */
    @Test
    fun `swiping moves the deck forward`() {
        compose.setContent { OnboardingScreen(onDone = {}) }

        compose.onRoot().performTouchInput { swipeLeft() }

        compose.onNodeWithText("Walk the room").assertIsDisplayed()
    }

    /**
     * Swiping back is the whole reason there is no Back button.
     *
     * If the pager ever loses its gesture — a `userScrollEnabled = false` added for some
     * unrelated reason, a parent that eats horizontal drags — the deck becomes one-way and
     * nothing on the screen says so. This is the test that notices.
     */
    @Test
    fun `swiping back returns to the previous card, which is why there is no back button`() {
        compose.setContent { OnboardingScreen(onDone = {}) }

        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Walk the room").assertIsDisplayed()
        compose.onNodeWithText("Back").assertDoesNotExist()

        compose.onRoot().performTouchInput { swipeRight() }

        compose.onNodeWithText("Expect about ±2–3 cm").assertIsDisplayed()
    }

    /**
     * Skip is on every card except the last, and it finishes.
     *
     * A tutorial nobody can escape earns its own one-star reviews. The last card has no
     * Skip because its primary button already ends the deck, and two controls that do the
     * same thing side by side is a choice nobody needs to make.
     */
    @Test
    fun `skip finishes from the first card`() {
        var done = false
        compose.setContent { OnboardingScreen(onDone = { done = true }) }

        compose.onNodeWithText("Skip").performClick()

        assertTrue("Skip must actually finish, not merely advance.", done)
    }

    /**
     * Every control is within thumb reach, and Skip is the quiet one.
     *
     * Asserted on geometry rather than trusted to the layout code, because this is a screen
     * held one-handed by somebody standing in a room, and "the buttons drifted back to the
     * top" is a regression that looks perfectly fine in a screenshot.
     */
    @Test
    fun `the actions sit at the bottom with skip to the left of the primary`() {
        compose.setContent { OnboardingScreen(onDone = {}) }

        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        val skip = compose.onNodeWithText("Skip").getUnclippedBoundsInRoot()
        val next = compose.onNodeWithText("Next").getUnclippedBoundsInRoot()

        assertTrue(
            "Skip is meant to be tertiary and to the left of the primary action.",
            skip.left < next.left,
        )
        assertTrue(
            "Both actions belong in the bottom quarter of the screen, near the thumb.",
            skip.top > screen.height * 0.75f && next.top > screen.height * 0.75f,
        )
    }

    /** Nothing competes with the button that ends the deck. */
    @Test
    fun `skip leaves on the last card`() {
        compose.setContent { OnboardingScreen(onDone = {}) }

        repeat(STEP_COUNT - 1) { compose.onNodeWithText("Next").performClick() }

        compose.onNodeWithText("Skip").assertDoesNotExist()
    }

    @Test
    fun `finishing the last card reports done`() {
        var done = false
        compose.setContent { OnboardingScreen(onDone = { done = true }) }

        repeat(STEP_COUNT - 1) { compose.onNodeWithText("Next").performClick() }
        compose.onNodeWithText("Start measuring").performClick()

        assertTrue("The end of the deck must actually finish.", done)
    }

    @Test
    fun `reopened later it does not pretend to be a first run`() {
        compose.setContent { OnboardingScreen(onDone = {}, firstRun = false) }

        repeat(STEP_COUNT - 1) { compose.onNodeWithText("Next").performClick() }
        // "Start measuring" would be wrong here: the user opened this from a home screen
        // they were already using, most likely mid-way through fixing a bad capture.
        compose.onNodeWithText("Done").assertIsDisplayed()
    }

    private companion object {
        /** Accuracy, then the four things that decide a capture. */
        const val STEP_COUNT = 5
    }
}
