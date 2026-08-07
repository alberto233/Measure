package com.measure.feature.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The reticle, including the state nobody can otherwise see without a phone and a room.
 *
 * The rectilinear assist moves the placed corner away from the pixel being aimed at. That
 * is the feature, and it is also exactly what a broken app looks like — so the lock ring
 * doing its job is load-bearing, and it is drawn in a `Canvas` where nothing else can
 * check it. A picture is the only review this code can get before it reaches hardware.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ReticleScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `on a target`() = capture("reticle-target", snapped = false)

    @Test
    fun `held by the rectilinear assist`() = capture("reticle-snapped", snapped = true)

    private fun capture(name: String, snapped: Boolean) {
        compose.setContent {
            // A mid grey stands in for the camera image: the reticle carries a dark halo
            // specifically so it survives a bright wall, and drawing it on white or black
            // would flatter one half of that.
            Box(Modifier.size(160.dp).background(Color(0xFF7A7A7A))) {
                Reticle(
                    ready = true,
                    hasTarget = true,
                    samplingProgress = null,
                    snapped = snapped,
                    modifier = Modifier.size(160.dp),
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
}
