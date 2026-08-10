package com.traza.feature.onboarding

import android.content.Context

/**
 * Remembers whether the guidance has been shown.
 *
 * **Deliberately not in the Room database.** Whether someone has read a tutorial is not
 * something they measured, and putting it in `measure.db` would drag it into the schema,
 * the migration surface and the export. One boolean is not worth a version bump, and a
 * migration that exists to carry a "seen the tutorial" flag is a migration that can lose
 * somebody's rooms. `SharedPreferences` is the right size of tool.
 *
 * Its own file rather than sharing one with unrelated settings, so clearing it in a test
 * — or on the device, via the app's storage settings — has no other consequences.
 */
class OnboardingStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * False on a fresh install, and on any install that predates this screen existing.
     *
     * Existing testers therefore see the guidance once on their next update, which is the
     * right way round: they are exactly the people who have hit the mistakes it describes.
     */
    val hasSeenGuidance: Boolean
        get() = preferences.getBoolean(KEY_SEEN, false)

    fun markGuidanceSeen() {
        preferences.edit().putBoolean(KEY_SEEN, true).apply()
    }

    private companion object {
        const val FILE = "onboarding"
        const val KEY_SEEN = "seen-guidance"
    }
}
