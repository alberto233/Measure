package com.measure.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.measure.feature.capture.CaptureScreen

/**
 * Hosts the AR capture screen.
 *
 * Two window decisions, both about the act of measuring rather than about styling:
 *
 * - **Portrait only.** Rotating mid-measurement re-lays-out the overlay and forces ARCore
 *   to re-derive its display geometry at the exact moment the user is holding still. The
 *   phone is held upright to aim at a wall anyway. Locked in the manifest.
 * - **Screen stays on.** Capture involves aiming, walking and holding still, none of which
 *   the system counts as interaction. Letting the display sleep mid-measurement would
 *   drop the AR session and lose the tracking it had built up.
 */
class CaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            CaptureScreen(onExit = { finish() })
        }
    }
}
