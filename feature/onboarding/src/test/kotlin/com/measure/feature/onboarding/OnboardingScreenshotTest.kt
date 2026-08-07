package com.measure.feature.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the first thing a new user sees.
 *
 * Worth taking for this screen in particular. It is dense with prose, which is the content
 * most likely to overflow, collide or clip once it meets a real font and a real screen
 * width — and unlike the rest of the app it has no numbers to look wrong, so a layout
 * fault here is invisible in a diff and obvious in a picture.
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
    fun `first run`() {
        compose.setContent { OnboardingScreen(onDone = {}) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/onboarding-first-run.png")
    }

    @Test
    fun `reopened as reference`() {
        compose.setContent { OnboardingScreen(onDone = {}, firstRun = false) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/onboarding-reference.png")
    }
}
