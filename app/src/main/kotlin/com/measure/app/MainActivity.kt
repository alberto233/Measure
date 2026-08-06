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
import android.view.View
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
    private lateinit var clearCrashButton: Button

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
            crashSection()?.let { appendLine(it) }
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

    /**
     * The last crash, if there was one. Shown first and in full, because on a sideloaded
     * build this is the only place the stack trace can be read at all.
     */
    private fun crashSection(): String? {
        val crash = CrashLog.read(this) ?: return null
        return buildString {
            appendLine("PREVIOUS RUN CRASHED")
            appendLine(crash.trimEnd())
            appendLine("─".repeat(48))
        }
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

        clearCrashButton.visibility =
            if (CrashLog.read(this) != null) View.VISIBLE else View.GONE

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

    /**
     * A scrolling report with a **fixed button bar underneath**.
     *
     * The buttons used to sit inside the scroll region, which put the primary action
     * above a report long enough to need scrolling: it slid under the title bar and its
     * label became unreadable. Actions that are always available should always be
     * visible, and the bottom of the screen is also where a thumb already is.
     */

    // --- direction A, in plain views ------------------------------------------------
    //
    // Mirrors :core:designsystem rather than importing it: this screen is not Compose, and
    // pulling a Compose module into :app for four colours would be the wrong trade for a
    // file that is going to be ported anyway. If the palette moves, it moves here too —
    // which is a real duplication, and the reason porting this screen is still on the list.

    private val SURFACE = Color.parseColor("#FF0B0B0C")
    private val PANEL = Color.parseColor("#FF141416")
    private val LINE = Color.parseColor("#FF2A2A2E")
    private val INK = Color.parseColor("#FFF2F2F0")
    private val MUTED = Color.parseColor("#FF8A8A90")
    private val ACCENT = Color.parseColor("#FFFF4A1C")

    /** The primary action: filled, hard-edged, and the only accent on the screen. */
    private fun accentButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label.uppercase()
            isAllCaps = false
            letterSpacing = 0.08f
            setTextColor(SURFACE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ACCENT)
                cornerRadius = dp(2).toFloat()
            }
            setOnClickListener { onClick() }
        }

    /** Everything else: a bordered box, so a control looks machined rather than moulded. */
    private fun outlineButton(label: String): Button =
        Button(this).apply {
            text = label.uppercase()
            isAllCaps = false
            letterSpacing = 0.08f
            setTextColor(INK)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(PANEL)
                cornerRadius = dp(2).toFloat()
                setStroke(dp(1), LINE)
            }
        }

    private fun buildLayout(): LinearLayout {
        val padding = dp(20)

        val eyebrow = TextView(this).apply {
            text = "DEVICE CAPABILITY"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            letterSpacing = 0.12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(MUTED)
        }

        val title = TextView(this).apply {
            text = "What this phone can do"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(INK)
            setPadding(0, dp(4), 0, dp(16))
        }

        // Monospaced, and now on the app's own ground rather than the platform's.
        //
        // This screen is still plain Android views while everything else is Compose — the
        // port is M10b's last job. Until then it at least stops being the one light screen
        // in a dark app, which is what it looked like: the manifest gives it
        // Theme.Material.Light, so it arrived white with black text in an app that is
        // near-black everywhere else.
        reportView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(MUTED)
            setTextIsSelectable(true)
        }

        measureButton = accentButton("Start measuring") {
            startActivity(Intent(this@MainActivity, CaptureActivity::class.java))
        }

        actionButton = outlineButton("Re-run checks")

        clearCrashButton = outlineButton("Clear crash report").apply {
            visibility = View.GONE
            setOnClickListener {
                CrashLog.clear(this@MainActivity)
                refresh()
            }
        }

        val scrollingReport = ScrollView(this).apply {
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(padding, padding, padding, padding)
                    addView(eyebrow)
                    addView(title)
                    addView(reportView)
                },
            )
        }

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(12), padding, padding)
            addView(measureButton, barParams())
            addView(actionButton, barParams(topMargin = dp(8)))
            addView(clearCrashButton, barParams(topMargin = dp(8)))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE)
            addView(
                scrollingReport,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    // The report absorbs all the spare height; the bar keeps its own.
                    1f,
                ),
            )
            addView(
                buttonBar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun barParams(topMargin: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { this.topMargin = topMargin }

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
