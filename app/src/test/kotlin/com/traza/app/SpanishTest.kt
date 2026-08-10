package com.traza.app

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.ar.core.ArCoreApk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app on a Spanish phone.
 *
 * `TranslationTest` proves the string files agree with each other, which is a claim about two
 * XML documents and not about the app. This is the other half: a screen rendered under a
 * Spanish locale, reading its text through the same path a phone does. The two together are
 * what makes "the app is translated" a checked statement rather than a hopeful one.
 *
 * The device check is the screen chosen for it because it is the first one a new user sees
 * and because it is almost entirely prose — every one of its verdicts is a sentence, so a
 * locale that failed to resolve would be obvious rather than subtle.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "es-rES-w411dp-h891dp-xhdpi")
class SpanishTest {

    @get:Rule
    val compose = createComposeRule()

    private val resources
        get() = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun `the device check speaks Spanish`() {
        val report = DeviceCheck.of(
            resources = resources,
            runCount = 1,
            stamp = "09:41:07",
            availability = ArCoreApk.Availability.SUPPORTED_INSTALLED,
            hasCamera = true,
            depth = DepthSupport(automatic = true, raw = true),
            crash = null,
        )

        compose.setContent {
            DeviceCheckScreen(
                report = report,
                onStartMeasuring = {},
                onPrimaryCheckAction = {},
                onClearCrash = {},
            )
        }

        // The heading, the verdict and the primary action: one from the screen, one from
        // DeviceCheck's own mapping, and one from a `when` that picks between three
        // resources. Each reaches its string by a different route, and all three have been
        // wrong at least once in some codebase.
        compose.onNodeWithText("Qué puede hacer este móvil").assertIsDisplayed()
        compose.onNodeWithText("Totalmente admitido").assertIsDisplayed()
        compose.onNodeWithText("Empezar a medir").assertIsDisplayed()

        compose.onRoot().captureRoboImage("build/outputs/roborazzi/device-check-es.png")
    }
}
