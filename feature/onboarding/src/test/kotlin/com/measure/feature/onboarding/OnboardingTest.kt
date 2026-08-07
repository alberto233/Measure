package com.measure.feature.onboarding

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    @Test
    fun `the accuracy figure is stated before anything else`() {
        compose.setContent { OnboardingScreen(onDone = {}) }

        // The one sentence the screen exists to deliver. If this ever quietly stops being
        // shown, the whole mitigation for "users expect LiDAR precision" is gone while
        // the screen still looks fine.
        compose.onNodeWithText("Expect about ±2–3 cm").assertIsDisplayed()
    }

    /**
     * Scrolls to each point, because on a 411×891 screen only the first two fit.
     *
     * That is by design — the page scrolls and the button stays pinned below it — but it
     * is worth being explicit that the later points are reachable rather than merely
     * present in the tree. "Move slowly" failed this test before the scroll was added,
     * which is exactly the distinction being drawn.
     */
    @Test
    fun `every guidance point can be reached`() {
        compose.setContent { OnboardingScreen(onDone = {}) }

        for (title in listOf(
            "Walk the room",
            "Give it light and detail",
            "Move slowly",
            "Aim where the wall meets the floor",
        )) {
            compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `finishing the first run reports done`() {
        var done = false
        compose.setContent { OnboardingScreen(onDone = { done = true }) }

        compose.onNodeWithText("Start measuring").performClick()

        assertTrue("The only way off this screen must actually fire.", done)
    }

    @Test
    fun `reopened later it does not pretend to be a first run`() {
        compose.setContent { OnboardingScreen(onDone = {}, firstRun = false) }

        // "Start measuring" would be wrong here: the user opened this from a home screen
        // they were already using, most likely mid-way through fixing a bad capture.
        compose.onNodeWithText("Done").assertIsDisplayed()
    }
}
