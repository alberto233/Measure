package com.traza.core.data

import android.content.Context
import com.traza.core.geometry.capture.Calibration

/**
 * Remembers this phone's scale correction — `docs/ACCURACY.md` M9.
 *
 * **Per device, not per project, and deliberately not in `measure.db`.** A calibration is a
 * fact about the handset rather than about anything the user measured, so it does not belong
 * in the schema, the migrations or the export — the same argument `OnboardingStore` makes,
 * and for the same reason: a migration that exists to carry one number is a migration that
 * can lose somebody's rooms.
 *
 * **It is never applied retroactively.** Saved plans keep the lengths they were captured
 * with. Anything else means a stored measurement silently changes value between one launch
 * and the next, which is precisely the behaviour the corner assist had to have removed from
 * it after a field test: a record of what the room was should not move because a setting did.
 * The correction applies to points as they are captured, and a plan measured before
 * calibration stays as measured.
 */
class CalibrationStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** [Calibration.NONE] until somebody calibrates, which most people never will. */
    var calibration: Calibration
        get() = Calibration(
            preferences.getFloat(KEY_SCALE, Calibration.NONE.scale.toFloat()).toDouble(),
        )
        set(value) {
            preferences.edit().putFloat(KEY_SCALE, value.scale.toFloat()).apply()
        }

    /** Whether this device carries a correction at all, for an interface that says so. */
    val isCalibrated: Boolean get() = !calibration.isIdentity

    fun clear() {
        preferences.edit().remove(KEY_SCALE).apply()
    }

    private companion object {
        const val FILE = "calibration"

        /**
         * Stored as a float, which holds a scale factor to about seven digits — far finer
         * than the third decimal place this number is ever known to.
         */
        const val KEY_SCALE = "scale"
    }
}
