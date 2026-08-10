package com.measure.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.ar.core.ArCoreApk
import com.measure.core.data.CalibrationStore
import com.measure.core.geometry.capture.Calibration
import com.measure.core.geometry.capture.CalibrationOutcome
import com.measure.core.geometry.capture.Refusal
import com.measure.core.units.LengthParser
import com.google.ar.core.Config
import com.google.ar.core.Session
import java.util.Date
import java.util.Locale

/**
 * The capability gate and the launcher for the capture screen.
 *
 * Before any measuring work is worth doing we need a definitive answer to two questions
 * about the actual handset: does ARCore support it, and does it support the Depth API.
 * Published lists go stale — the community mirror has no device newer than early 2024 —
 * so the only trustworthy check is the one the device performs on itself.
 *
 * This class only runs the checks. [DeviceCheck] decides what the answers mean and
 * `DeviceCheckScreen` draws them, which is what lets the states this machine cannot
 * produce — no ARCore, unsupported device, a session that will not open — be rendered and
 * looked at rather than reasoned about.
 *
 * It was the last screen in the app built from plain Android views, and the duplicate
 * palette that came with it — six `Color.parseColor` constants mirroring
 * `:core:designsystem` — is gone with it.
 */
class MainActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Stamped onto each report. The checks are deterministic, so without a visible
     * change every re-run looks identical and the button appears dead.
     *
     * Declared before [report], whose initialiser reads it. Kotlin initialises properties
     * in declaration order and would otherwise hand back a silent zero.
     */
    private var runCount = 0

    private var report by mutableStateOf(DeviceCheck.checking(resources, runCount, stamp()))

    /** ARCore's install flow may only be requested once per user gesture. */
    private var userRequestedInstall = true

    private var pendingRecheck: Runnable? = null

    /** What the bottom action does, which depends on what the checks found. */
    private var action: () -> Unit = ::refresh

    private val calibrationStore by lazy { CalibrationStore(this) }

    private var calibration by mutableStateOf(Calibration.NONE)

    /**
     * What the last calibration attempt did, or why it did nothing.
     *
     * Held rather than shown as a toast because most attempts are *refused*, and every
     * refusal is a sentence worth reading twice — "those two agree to within the margin this
     * app already expects" is an explanation, not a beep.
     */
    private var calibrationNote by mutableStateOf<String?>(null)

    /**
     * The Activity Result API rather than `requestPermissions`, which the plain-view
     * version used. The answer arrives on this launcher instead of in an override, which
     * is the whole reason the framework's version is deprecated.
     */
    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DeviceCheckScreen(
                report = report,
                onStartMeasuring = {
                    startActivity(Intent(this, CaptureActivity::class.java))
                },
                onPrimaryCheckAction = { action() },
                onClearCrash = {
                    CrashLog.clear(this)
                    refresh()
                },
                calibration = calibration,
                onCalibrate = ::calibrate,
                onClearCalibration = {
                    calibrationStore.clear()
                    calibration = Calibration.NONE
                    calibrationNote = getString(R.string.calibration_removed)
                },
                calibrationNote = calibrationNote,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        calibration = calibrationStore.calibration
        requestCameraPermissionIfNeeded()
        refresh()
    }

    /**
     * Take the two typed figures and either store a correction or say why not.
     *
     * The deciding is `Calibration.of`, in `:core:geometry`, where it is tested against every
     * refusal without a device. All this does is turn text into metres — through
     * `LengthParser`, so a Spanish phone's "2,05" is read as a length and not as nothing —
     * and turn the answer back into a sentence.
     */
    private fun calibrate(measuredText: String, actualText: String) {
        val measured = LengthParser.parseMetric(measuredText)?.metres
        val actual = LengthParser.parseMetric(actualText)?.metres

        if (measured == null || actual == null) {
            calibrationNote = getString(R.string.calibration_not_a_length)
            return
        }

        when (val outcome = Calibration.of(measured, actual)) {
            is CalibrationOutcome.Calibrated -> {
                calibrationStore.calibration = outcome.calibration
                calibration = outcome.calibration
                calibrationNote = getString(R.string.calibration_applied)
            }

            is CalibrationOutcome.Refused -> {
                calibrationNote = getString(
                    when (outcome.reason) {
                        Refusal.NOT_A_LENGTH -> R.string.calibration_not_a_length
                        Refusal.REFERENCE_TOO_SHORT -> R.string.calibration_too_short
                        Refusal.WITHIN_NOISE -> R.string.calibration_within_noise
                        Refusal.IMPLAUSIBLE -> R.string.calibration_implausible
                    },
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        cancelPendingRecheck()
    }

    // --- the checks ---------------------------------------------------------------

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
            val message = "${error.javaClass.simpleName}: ${error.message ?: noMessage()}"
            report = DeviceCheck.failed(resources, runCount, stamp(), message, CrashLog.read(this))
            action = ::refresh
            return
        }

        // The Play Store lookup is asynchronous the first time. Poll until it settles.
        if (availability == ArCoreApk.Availability.UNKNOWN_CHECKING) {
            report = DeviceCheck.checking(resources, runCount, stamp())
            val recheck = Runnable { refresh() }
            pendingRecheck = recheck
            handler.postDelayed(recheck, RECHECK_DELAY_MS)
            return
        }

        val hasCamera = hasCameraPermission()
        val depth = if (availability == ArCoreApk.Availability.SUPPORTED_INSTALLED && hasCamera) {
            probeDepthSupport()
        } else {
            null
        }

        report = DeviceCheck.of(
            resources = resources,
            runCount = runCount,
            stamp = stamp(),
            availability = availability,
            hasCamera = hasCamera,
            depth = depth,
            crash = CrashLog.read(this),
        )
        action = when {
            availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ||
                availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> ::requestArCoreInstall

            !hasCamera -> ::requestCameraPermissionIfNeeded
            else -> ::refresh
        }
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
            DepthSupport(error = "${error.javaClass.simpleName}: ${error.message ?: noMessage()}")
        } finally {
            try {
                session?.close()
            } catch (ignored: Throwable) {
                // Nothing useful to do if teardown fails; the report is already written.
            }
        }
    }

    private fun requestArCoreInstall() {
        try {
            ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)
            userRequestedInstall = false
        } catch (error: Throwable) {
            val message = getString(
                R.string.device_check_install_failed,
                error.message ?: noMessage(),
            )
            report = DeviceCheck.failed(resources, runCount, stamp(), message, CrashLog.read(this))
        }
    }

    // --- permissions --------------------------------------------------------------

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermissionIfNeeded() {
        if (!hasCameraPermission()) cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun noMessage(): String = getString(R.string.device_check_no_message)

    private fun stamp(): String =
        java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private companion object {
        /** Long enough not to spin, short enough that the screen does not look stuck. */
        const val RECHECK_DELAY_MS = 250L
    }
}
