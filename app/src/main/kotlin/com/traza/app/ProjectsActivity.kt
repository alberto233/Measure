package com.traza.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.traza.feature.onboarding.OnboardingScreen
import com.traza.feature.onboarding.OnboardingStore
import com.traza.feature.projects.ProjectsScreen

/**
 * The home screen: saved plans, and the way into a new one.
 *
 * This is the launcher activity rather than the capability report. The report still
 * exists and is one tap away, but it guards the *camera*, and refusing to show someone
 * the plans they have already captured because ARCore is unavailable would be the wrong
 * trade. Every ARCore failure is reported by the capture screen itself.
 */
class ProjectsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // The same fix EditorActivity needed, applied before this screen could reproduce
        // the fault rather than after. With edge-to-edge on, letting the window resize for
        // the keyboard means the inset is counted twice — once by the resize and again by
        // `safeDrawingPadding` — and the content ends up squashed into the top of the
        // screen. This screen gained its first text field with search, so it was one step
        // away from the same bug. Nothing here needs to move for the keyboard: the search
        // field is at the top and stays visible.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        val onboarding = OnboardingStore(this)

        setContent {
            // Shown before the list on a first run, and reopenable from it afterwards.
            //
            // Held here rather than inside ProjectsScreen so the screen stays a pure
            // function of its arguments — it is the one with screenshot tests, and a
            // screen that reads a preference file to decide what to draw cannot be
            // rendered from a test without that file existing.
            var showGuidance by rememberSaveable { mutableStateOf(!onboarding.hasSeenGuidance) }

            if (showGuidance) {
                OnboardingScreen(
                    firstRun = !onboarding.hasSeenGuidance,
                    onDone = {
                        onboarding.markGuidanceSeen()
                        showGuidance = false
                    },
                )
            } else {
                ProjectsScreen(
                    onNewMeasurement = { startCapture(projectId = null) },
                    // Opening a saved plan goes to the editor, not back to the camera. The
                    // reason to reopen something already measured is almost always to look
                    // at it or correct it; adding another room is a button away from there.
                    onOpenProject = { openEditor(it) },
                    onDeviceCheck = { startActivity(Intent(this, MainActivity::class.java)) },
                    onGuidance = { showGuidance = true },
                )
            }
        }
    }

    private fun openEditor(projectId: Long) {
        startActivity(
            Intent(this, EditorActivity::class.java)
                .putExtra(EditorActivity.EXTRA_PROJECT_ID, projectId),
        )
    }

    private fun startCapture(projectId: Long?) {
        startActivity(
            Intent(this, CaptureActivity::class.java).apply {
                if (projectId != null) putExtra(CaptureActivity.EXTRA_PROJECT_ID, projectId)
            },
        )
    }
}
