package com.measure.app

import android.content.Intent
import android.os.Bundle
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

        setContent {
            ProjectsScreen(
                onNewMeasurement = { startCapture(projectId = null) },
                onOpenProject = { startCapture(projectId = it) },
                onDeviceCheck = { startActivity(Intent(this, MainActivity::class.java)) },
            )
        }
    }

    private fun startCapture(projectId: Long?) {
        startActivity(
            Intent(this, CaptureActivity::class.java).apply {
                if (projectId != null) putExtra(CaptureActivity.EXTRA_PROJECT_ID, projectId)
            },
        )
    }
}
