package com.measure.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.measure.core.geometry.CapturedCorner
import com.measure.core.geometry.RoomCapture
import com.measure.core.geometry.RoomSolver
import com.measure.core.geometry.Vec2
import com.measure.core.units.AreaFormatter
import com.measure.core.units.Length
import com.measure.core.units.LengthFormatter
import com.measure.core.units.UnitSystem
import java.util.Date
import java.util.Locale

/**
 * The capability gate and the launcher for the capture screen.
 *
 * Before any measuring work is worth doing we need a definitive answer to two questions
 * about the actual handset: does ARCore support it, and does it support the Depth API.
 * Published lists go stale — the community mirror has no device newer than early 2024 —
 * so the only trustworthy check is the one the device performs on itself. Capture is
 * offered only once those checks pass, which is why this screen still exists now that
 * there is a real UI behind it: opening a session we already know will fail is how
 * competitors earn their crash-on-scan-start reviews.
 *
 * Still plain Android views rather than Compose. That began as a constraint — the
 * development container could not resolve AndroidX — and survives it as a choice: this is
 * a diagnostic screen, it is the one thing that must render even when everything else is
 * broken, and it depends on nothing but the framework and ARCore. A proper home screen
 * replaces it in a later milestone.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var reportView: TextView
    private lateinit var actionButton: Button
    private lateinit var measureButton: Button

    /** ARCore's install flow may only be requested once per user gesture. */
    private var userRequestedInstall = true

    private var pendingRecheck: Runnable? = null

    /**
     * Stamped onto each report. The checks are deterministic, so without a visible
     * change every re-run looks identical and the button appears dead.
     */
    private var runCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
    }

    override fun onResume() {
        super.onResume()
        requestCameraPermissionIfNeeded()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        cancelPendingRecheck()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    // --- report -------------------------------------------------------------------

    /** Written out rather than as `handler::removeCallbacks`, which is overloaded. */
    private fun cancelPendingRecheck() {
        val scheduled = pendingRecheck ?: return
        handler.removeCallbacks(scheduled)
        pendingRecheck = null
    }

    private fun refresh() {
        cancelPendingRecheck()
        runCount++

        val availability = try {
            ArCoreApk.getInstance().checkAvailability(this)
        } catch (error: Throwable) {
            reportView.text = buildString {
                appendLine(deviceSection())
                appendLine("ARCore")
                appendLine("  Availability check failed: ${error.javaClass.simpleName}")
                appendLine("  ${error.message ?: "no message"}")
                appendLine()
                append(geometrySection())
            }
            return
        }

        // The Play Store lookup is asynchronous the first time. Poll until it settles.
        if (availability.isTransient) {
            reportView.text = "${deviceSection()}\nARCore\n  Checking with Google Play…"
            val recheck = Runnable { refresh() }
            pendingRecheck = recheck
            handler.postDelayed(recheck, 250)
            return
        }

        val hasCamera = hasCameraPermission()
        val depth = if (availability == ArCoreApk.Availability.SUPPORTED_INSTALLED && hasCamera) {
            probeDepthSupport()
        } else {
            null
        }

        reportView.text = buildString {
            appendLine(deviceSection())
            appendLine("ARCore")
            appendLine("  Availability: ${describe(availability)}")
            appendLine("  Camera permission: ${if (hasCamera) "granted" else "not granted"}")
            when {
                depth == null && !hasCamera ->
                    appendLine("  Depth API: needs camera permission to test")

                depth == null ->
                    appendLine("  Depth API: not testable until ARCore is installed")

                depth.error != null -> {
                    appendLine("  Depth API: session failed")
                    appendLine("    ${depth.error}")
                }

                else -> {
                    appendLine("  Depth API (AUTOMATIC): ${yesNo(depth.automatic)}")
                    appendLine("  Raw Depth API: ${yesNo(depth.raw)}")
                }
            }
            appendLine()
            appendLine(verdict(availability, depth))
            appendLine()
            append(geometrySection())
        }

        configureActionButton(availability, hasCamera)
    }

    private fun deviceSection(): String = buildString {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        appendLine("Check #$runCount at $stamp")
        appendLine()
        appendLine("Device")
        appendLine("  ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("  Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("  ${Build.SUPPORTED_ABIS.joinToString(", ")}")
    }

    private fun probeDepthSupport(): DepthSupport {
        var session: Session? = null
        return try {
            session = Session(this)
            DepthSupport(
                automatic = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC),
                raw = session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY),
            )
        } catch (error: Throwable) {
            DepthSupport(error = "${error.javaClass.simpleName}: ${error.message ?: "no message"}")
        } finally {
            try {
                session?.close()
            } catch (ignored: Throwable) {
                // Nothing useful to do if teardown fails; the report is already written.
            }
        }
    }

    /**
     * Runs the pure-Kotlin measurement core on the handset, on a synthetic room with
     * the same noise and drift used in the JVM tests. It proves the core is wired in
     * and behaves identically on ARM as it does in CI.
     */
    private fun geometrySection(): String = try {
        val truth = listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 4.0), Vec2(0.0, 4.0))
        val drift = Vec2(0.10, -0.06)
        val noise = listOf(
            Vec2(0.021, -0.014), Vec2(-0.018, 0.009),
            Vec2(0.012, 0.022), Vec2(-0.008, -0.019),
        )
        val perimeter = 18.0
        var travelled = 0.0

        val corners = truth.mapIndexed { index, corner ->
            if (index > 0) travelled += truth[index].distanceTo(truth[index - 1])
            CapturedCorner(corner + drift * (travelled / perimeter) + noise[index], sigma = 0.02)
        }
        val solution = RoomSolver.solve(RoomCapture(corners, truth.first() + drift))

        buildString {
            appendLine("Measurement core (synthetic 5.00 x 4.00 m room)")
            solution.polygon.edges.forEachIndexed { index, edge ->
                appendLine("  Wall $index: ${LengthFormatter.formatMetric(Length(edge.length))}")
            }
            appendLine("  Area: ${AreaFormatter.format(solution.area, UnitSystem.METRIC)} (true 20 m²)")
            val misclosure = String.format(
                Locale.getDefault(), "%.1f", solution.closure.relativeError * 100,
            )
            appendLine("  Misclosure: $misclosure% of perimeter")
            append("  Reliable: ${yesNo(solution.isReliable)}")
        }
    } catch (error: Throwable) {
        "Measurement core failed: ${error.javaClass.simpleName}: ${error.message}"
    }

    private fun verdict(availability: ArCoreApk.Availability, depth: DepthSupport?): String = when {
        availability == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE ->
            "VERDICT: this device cannot run ARCore. Measuring by camera is not possible here."

        availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ->
            "VERDICT: supported, but Google Play Services for AR is not installed yet."

        availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD ->
            "VERDICT: supported, but Google Play Services for AR needs updating."

        depth?.automatic == true ->
            "VERDICT: fully supported, Depth API included. Everything in the plan is achievable."

        depth != null && depth.error == null ->
            "VERDICT: ARCore works but without the Depth API. Plane-based measuring only."

        else ->
            "VERDICT: ARCore is supported. Grant camera access to test the Depth API."
    }

    private fun configureActionButton(availability: ArCoreApk.Availability, hasCamera: Boolean) {
        // Capture is offered only once the device has actually proved it can do it.
        // Opening an AR session we already know will fail is how competitors produce the
        // crash-on-scan-start reviews in docs/PRODUCT_PLAN.md §4.
        val canMeasure = availability == ArCoreApk.Availability.SUPPORTED_INSTALLED && hasCamera
        measureButton.isEnabled = canMeasure
        measureButton.text = if (canMeasure) "Start measuring" else "Measuring unavailable"

        when {
            availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ||
                availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> {
                actionButton.text = "Install or update ARCore"
                actionButton.isEnabled = true
                actionButton.setOnClickListener { requestArCoreInstall() }
            }

            !hasCamera -> {
                actionButton.text = "Grant camera permission"
                actionButton.isEnabled = true
                actionButton.setOnClickListener { requestCameraPermissionIfNeeded() }
            }

            else -> {
                actionButton.text = "Re-run checks"
                actionButton.isEnabled = true
                actionButton.setOnClickListener { refresh() }
            }
        }
    }

    private fun requestArCoreInstall() {
        try {
            ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)
            userRequestedInstall = false
        } catch (error: Throwable) {
            reportView.text = "ARCore install request failed:\n${error.message}"
        }
    }

    // --- permissions --------------------------------------------------------------

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermissionIfNeeded() {
        if (!hasCameraPermission()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_CODE)
        }
    }

    // --- layout -------------------------------------------------------------------

    private fun buildLayout(): ScrollView {
        val padding = dp(20)

        val title = TextView(this).apply {
            text = "Measure — device capability"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(16))
        }

        reportView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.DKGRAY)
            setTextIsSelectable(true)
        }

        actionButton = Button(this).apply {
            text = "Re-run checks"
            setPadding(0, dp(16), 0, 0)
        }

        measureButton = Button(this).apply {
            text = "Start measuring"
            setOnClickListener { startActivity(Intent(this@MainActivity, CaptureActivity::class.java)) }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(title)
            addView(
                measureButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(20) },
            )
            addView(reportView)
            addView(
                actionButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(24) },
            )
        }

        return ScrollView(this).apply { addView(column) }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // --- helpers ------------------------------------------------------------------

    private data class DepthSupport(
        val automatic: Boolean = false,
        val raw: Boolean = false,
        val error: String? = null,
    )

    private val ArCoreApk.Availability.isTransient: Boolean
        get() = this == ArCoreApk.Availability.UNKNOWN_CHECKING

    private fun describe(availability: ArCoreApk.Availability): String = when (availability) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> "supported, installed"
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> "supported, not installed"
        ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> "supported, needs updating"
        ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> "NOT SUPPORTED on this device"
        ArCoreApk.Availability.UNKNOWN_CHECKING -> "checking"
        ArCoreApk.Availability.UNKNOWN_ERROR -> "unknown (error querying Play Services)"
        ArCoreApk.Availability.UNKNOWN_TIMED_OUT -> "unknown (timed out)"
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

    private companion object {
        const val CAMERA_REQUEST_CODE = 1001
    }
}
