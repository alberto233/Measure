package com.measure.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.measure.feature.editor.EditorScreen

/** Hosts the 2D plan editor for one project. */
class EditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val projectId = intent?.getLongExtra(EXTRA_PROJECT_ID, 0L) ?: 0L
        if (projectId == 0L) {
            finish()
            return
        }

        setContent {
            EditorScreen(
                projectId = projectId,
                onBack = { finish() },
                onAddRoom = {
                    startActivity(
                        Intent(this, CaptureActivity::class.java)
                            .putExtra(CaptureActivity.EXTRA_PROJECT_ID, projectId),
                    )
                },
            )
        }
    }

    companion object {
        const val EXTRA_PROJECT_ID = "com.measure.app.PROJECT_ID"
    }
}
