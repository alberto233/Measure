package com.measure.feature.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every card in the deck, drawings included.
 *
 * Worth taking for this screen above all others. It is the first thing a new user sees, it
 * is dense with prose that has to survive a real font at a real width, and its illustrations
 * are drawn in a `Canvas` — where no assertion can reach them and a wrong coordinate is
 * invisible in a diff and obvious in a picture.
 *
 * Nothing is asserted about pixels; see the note in the editor's equivalent. The value is
 * that somebody has looked.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class OnboardingScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `every card`() {
        compose.setContent { OnboardingScreen(onDone = {}) }

        for ((index, name) in NAMES.withIndex()) {
            if (index > 0) compose.onNodeWithText("Next").performClick()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/onboarding-$name.png")
        }
    }

    @Test
    fun `reopened as reference`() {
        compose.setContent { OnboardingScreen(onDone = {}, firstRun = false) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/onboarding-reference.png")
    }

    private companion object {
        val NAMES = listOf("1-tolerance", "2-walk", "3-light", "4-slow", "5-joint")
    }
}
