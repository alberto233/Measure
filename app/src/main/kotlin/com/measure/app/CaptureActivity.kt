package com.measure.app

import android.graphics.Typeface
import android.os.Bundle
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.measure.feature.capture.CaptureScreen
import java.io.PrintWriter
import java.io.StringWriter

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

        // Anything thrown while wiring the screen up — a missing class, a resource that
        // did not merge — would otherwise kill the process before a single pixel is
        // drawn, which from the outside looks exactly like the app closing for no
        // reason. Showing the trace instead keeps a sideloaded build diagnosable.
        //
        // This cannot catch failures during composition, which happen later on the main
        // looper; CrashLog is what covers those.
        try {
            enableEdgeToEdge()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContent {
                CaptureScreen(onExit = { finish() })
            }
        } catch (error: Throwable) {
            showStartupFailure(error)
        }
    }

    private fun showStartupFailure(error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        setContentView(
            ScrollView(this).apply {
                // The window is already edge to edge by this point, so without this the
                // first lines of the trace render underneath the status bar clock.
                fitsSystemWindows = true
                addView(
                    TextView(this@CaptureActivity).apply {
                        typeface = Typeface.MONOSPACE
                        setTextIsSelectable(true)
                        val pad = (16 * resources.displayMetrics.density).toInt()
                        setPadding(pad, pad, pad, pad)
                        text = "Capture screen failed to start\n\n$trace"
                    },
                )
            },
        )
    }
}
