package com.traza.core.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.traza.core.geometry.capture.Calibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The one number a phone remembers about itself.
 *
 * Small, and worth testing anyway for the same reason `OnboardingStore` is: a setting that
 * silently fails to persist looks exactly like a setting that works, right up until the user
 * relaunches. The difference only shows through a *second* instance, which is what these
 * assertions build.
 */
@RunWith(RobolectricTestRunner::class)
class CalibrationStoreTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun clearStoredCalibration() {
        // Robolectric keeps SharedPreferences for the life of the process.
        context.getSharedPreferences("calibration", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `a fresh install corrects nothing`() {
        val store = CalibrationStore(context)

        assertTrue(store.calibration.isIdentity)
        assertFalse(store.isCalibrated)
        assertEquals(4.2, store.calibration.apply(4.2), 1e-12)
    }

    @Test
    fun `a correction survives a new store, because that is the whole point of storing it`() {
        CalibrationStore(context).calibration = Calibration(0.98)

        // A second instance, as the next launch would build. Reading back through the same
        // object would pass even if nothing were ever written to disk.
        val next = CalibrationStore(context)
        assertEquals(0.98, next.calibration.scale, 1e-6)
        assertTrue(next.isCalibrated)
    }

    @Test
    fun `clearing goes back to correcting nothing`() {
        val store = CalibrationStore(context)
        store.calibration = Calibration(1.02)
        store.clear()

        assertTrue(CalibrationStore(context).calibration.isIdentity)
    }

    /**
     * A float holds the factor to far more precision than it is ever known to.
     *
     * Worth pinning because the stored type is narrower than the one in memory, and a
     * rounding error here would be a silent millimetre-per-metre error in every plan.
     */
    @Test
    fun `the stored factor survives the round trip to a float`() {
        CalibrationStore(context).calibration = Calibration(0.9873)

        assertEquals(0.9873, CalibrationStore(context).calibration.scale, 1e-6)
    }
}
