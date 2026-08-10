package com.measure.app

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.google.ar.core.ArCoreApk
import com.measure.core.geometry.capture.Calibration
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The calibration card, and the two sentences it must never lose.
 *
 * `CalibrationTest` proves the arithmetic and every refusal without a device. This is about
 * the other half — whether the screen says what the arithmetic decided, and whether it keeps
 * saying the two things that stop a correction being read as a promise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class CalibrationSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val resources
        get() = ApplicationProvider.getApplicationContext<Application>().resources

    private var applied: Pair<String, String>? = null

    private fun screen(calibration: Calibration = Calibration.NONE, note: String? = null) {
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
                calibration = calibration,
                onCalibrate = { measured, actual -> applied = measured to actual },
                calibrationNote = note,
            )
        }
    }

    private fun text(id: Int, vararg args: Any) = resources.getString(id, *args)

    /**
     * An uncalibrated phone says so plainly, rather than saying nothing.
     *
     * Silence would be read as "calibrated", which is the wrong way round: the default state
     * of every phone is uncorrected, and that is a fact about the numbers the user is looking
     * at elsewhere in the app.
     */
    @Test
    fun `an uncalibrated device says it is correcting nothing`() {
        screen()

        compose.onNodeWithText(text(R.string.calibration_none)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a calibrated device says which way it is out`() {
        // Scale 0.98 corrects a phone that reads about 2% long.
        screen(calibration = Calibration(0.98))

        compose.onNodeWithText(
            text(R.string.calibration_long, text(R.string.calibration_percent, 2.0408f)),
        ).performScrollTo().assertIsDisplayed()
    }

    /**
     * The two disclaimers are present in every state, calibrated or not.
     *
     * These are the whole reason this feature is safe to ship. One says a correction is not
     * applied backwards, so a saved plan does not change; the other says it removes bias and
     * not spread, so `±2–3 cm` still means what the guidance deck said it meant. A future
     * tidy-up that drops either of them turns an honest feature into the overclaim
     * `docs/PRODUCT_PLAN.md` §5 exists to prevent, and nothing else in the build would
     * notice.
     *
     * Two tests rather than a loop, because `setContent` may be called once per test.
     */
    @Test
    fun `the card says what a correction does not do, uncalibrated`() {
        screen(calibration = Calibration.NONE)
        assertDisclaimersShown()
    }

    @Test
    fun `the card says what a correction does not do, calibrated`() {
        screen(calibration = Calibration(0.98))
        assertDisclaimersShown()
    }

    private fun assertDisclaimersShown() {
        compose.onNodeWithText(text(R.string.calibration_not_retroactive))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.calibration_still_approximate))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a refusal is shown rather than swallowed`() {
        screen(note = text(R.string.calibration_within_noise))

        compose.onNodeWithText(text(R.string.calibration_within_noise))
            .performScrollTo().assertIsDisplayed()
    }

    /** What the user typed reaches the code that decides, unedited. */
    @Test
    fun `applying hands both typed figures up`() {
        screen()

        compose.onNodeWithText(text(R.string.calibration_measured_hint))
            .performScrollTo().performTextInput("2.09")
        compose.onNodeWithText(text(R.string.calibration_actual_hint))
            .performScrollTo().performTextInput("2.00")
        compose.onNodeWithText(text(R.string.calibration_apply)).performScrollTo().performClick()

        assertEquals("2.09" to "2.00", applied)
    }
}
