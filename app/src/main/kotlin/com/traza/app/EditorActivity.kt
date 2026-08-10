package com.traza.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.traza.feature.editor.EditorScreen

/** Hosts the 2D plan editor for one project. */
class EditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Edge-to-edge means Compose owns the insets, so the window must not *also*
        // resize itself for the keyboard. Left to resize, the keyboard's height was
        // subtracted twice — once by the window and again by safeDrawingPadding — and
        // the editing panel jumped to the top of the screen with a hand's width of
        // nothing between it and the keyboard.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

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
        const val EXTRA_PROJECT_ID = "com.traza.app.PROJECT_ID"
    }
}
