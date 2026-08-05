package com.measure.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.measure.feature.projects.ProjectsScreen

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

        setContent {
            ProjectsScreen(
                onNewMeasurement = { startCapture(projectId = null) },
                // Opening a saved plan goes to the editor, not back to the camera. The
                // reason to reopen something already measured is almost always to look
                // at it or correct it; adding another room is a button away from there.
                onOpenProject = { openEditor(it) },
                onDeviceCheck = { startActivity(Intent(this, MainActivity::class.java)) },
            )
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
