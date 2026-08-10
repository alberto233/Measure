package com.measure.app

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.ar.core.ArCoreApk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The device check, in each of the states it has.
 *
 * This screen is the one that most needed looking at and was the hardest to look at. Four
 * of its five states — no ARCore installed, an ARCore too old, a device ARCore does not
 * support, a session that will not open — cannot be produced on the A36 the app is tested
 * on, and cannot be produced at all on a build machine. They were written blind, shipped
 * blind, and would have stayed that way.
 *
 * They are renderable here only because `DeviceCheck` was separated from the code that
 * calls ARCore: the mapping from an availability value to a verdict is a pure function, so
 * every state is one call away. The alternative — an unsupported phone — is not something
 * this project can go and buy.
 *
 * Nothing is asserted about pixels. These exist to be seen, and once the appearance is
 * meant to hold still `verifyRoborazzi` turns them into regression tests unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class DeviceCheckScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** Robolectric's, which is where the locale under test comes from. */
    private val resources
        get() = ApplicationProvider.getApplicationContext<Application>().resources

    private fun shoot(report: DeviceReport, name: String) {
        compose.setContent {
            DeviceCheckScreen(
                report = report,
                onStartMeasuring = {},
                onPrimaryCheckAction = {},
                onClearCrash = {},
            )
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** The A36's own answer, and the only one this project has ever actually seen. */
    @Test
    fun `everything supported`() {
        shoot(
            DeviceCheck.of(
                resources = resources,
                runCount = 1,
                stamp = "09:41:07",
                availability = ArCoreApk.Availability.SUPPORTED_INSTALLED,
                hasCamera = true,
                depth = DepthSupport(automatic = true, raw = true),
                crash = null,
            ),
            "device-check-supported",
        )
    }

    /** Plane-based measuring only. The verdict has to say what that costs. */
    @Test
    fun `no depth api`() {
        shoot(
            DeviceCheck.of(
                resources = resources,
                runCount = 2,
                stamp = "09:41:07",
                availability = ArCoreApk.Availability.SUPPORTED_INSTALLED,
                hasCamera = true,
                depth = DepthSupport(automatic = false, raw = false),
                crash = null,
            ),
            "device-check-no-depth",
        )
    }

    @Test
    fun `arcore not installed`() {
        shoot(
            DeviceCheck.of(
                resources = resources,
                runCount = 3,
                stamp = "09:41:07",
                availability = ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
                hasCamera = true,
                depth = null,
                crash = null,
            ),
            "device-check-no-arcore",
        )
    }

    /** The one state where capture is refused outright. */
    @Test
    fun `device not capable`() {
        shoot(
            DeviceCheck.of(
                resources = resources,
                runCount = 4,
                stamp = "09:41:07",
                availability = ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE,
                hasCamera = true,
                depth = null,
                crash = null,
            ),
            "device-check-unsupported",
        )
    }

    @Test
    fun `camera permission refused`() {
        shoot(
            DeviceCheck.of(
                resources = resources,
                runCount = 5,
                stamp = "09:41:07",
                availability = ArCoreApk.Availability.SUPPORTED_INSTALLED,
                hasCamera = false,
                depth = null,
                crash = null,
            ),
            "device-check-no-camera",
        )
    }

    /**
     * With a crash report, which is the state this screen exists for on a sideloaded build.
     *
     * Worth its own picture because the trace is the one piece of content here with no
     * bound on its width or its length, and it sits above everything else.
     */
    @Test
    fun `after a crash`() {
        shoot(
            DeviceCheck.of(
                resources = resources,
                runCount = 6,
                stamp = "09:41:07",
                availability = ArCoreApk.Availability.SUPPORTED_INSTALLED,
                hasCamera = true,
                depth = DepthSupport(automatic = true, raw = true),
                crash = CRASH,
            ),
            "device-check-crash",
        )
    }

    private companion object {
        val CRASH = """
            2026-08-06 09:38:12 on main
            java.lang.IllegalStateException: no session
                at com.measure.ar.MeasureSession.frame(MeasureSession.kt:118)
                at com.measure.feature.capture.CaptureViewModel.tick(CaptureViewModel.kt:204)
                at android.view.Choreographer.doCallbacks(Choreographer.java:923)
        """.trimIndent()
    }
}
