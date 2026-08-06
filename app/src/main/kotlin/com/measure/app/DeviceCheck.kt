package com.measure.app

import android.os.Build
import androidx.compose.ui.graphics.Color
import com.google.ar.core.ArCoreApk
import com.measure.core.designsystem.MeasureColours
import com.measure.core.geometry.CapturedCorner
import com.measure.core.geometry.RoomCapture
import com.measure.core.geometry.RoomSolver
import com.measure.core.geometry.Vec2
import com.measure.core.units.AreaFormatter
import com.measure.core.units.Length
import com.measure.core.units.LengthFormatter
import com.measure.core.units.UnitSystem
import java.util.Locale

/** What an ARCore session said about depth, or why one could not be opened. */
data class DepthSupport(
    val automatic: Boolean = false,
    val raw: Boolean = false,
    val error: String? = null,
)

/**
 * What the capability checks mean, separated from the code that runs them.
 *
 * `MainActivity` asks ARCore and the permission system; this decides which answers are
 * fatal, which are merely awkward, and what to say about each. Separated because the
 * interesting states — an unsupported device, ARCore missing, a session that fails to open
 * — cannot be produced on the machines this is built on, and a screen whose four failure
 * states have never been rendered is a screen with four untested layouts in it. Here they
 * are all one function call away, which is what makes the screenshots in
 * `DeviceCheckScreenshotTest` possible.
 *
 * The colours are the measurement-state family from `docs/ACCURACY.md`, used deliberately:
 * this screen is the earliest statement of how good a measurement on this phone can be.
 */
object DeviceCheck {

    /** The full report, once the availability check has settled. */
    fun of(
        runCount: Int,
        stamp: String,
        availability: ArCoreApk.Availability,
        hasCamera: Boolean,
        depth: DepthSupport?,
        crash: String?,
    ): DeviceReport {
        val verdict = verdict(availability, depth)
        val needsArCore = availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ||
            availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD

        return DeviceReport(
            runCount = runCount,
            stamp = stamp,
            verdictHeadline = verdict.headline,
            verdictDetail = verdict.detail,
            verdictColour = verdict.colour,
            checks = checkLines(availability, hasCamera, depth),
            device = deviceLines(),
            core = coreLines(),
            crash = crash,
            // Capture is offered only once the device has actually proved it can do it.
            // Opening an AR session we already know will fail is how competitors produce
            // the crash-on-scan-start reviews in docs/PRODUCT_PLAN.md §4.
            canMeasure = availability == ArCoreApk.Availability.SUPPORTED_INSTALLED && hasCamera,
            actionLabel = when {
                needsArCore -> "Install or update ARCore"
                !hasCamera -> "Grant camera permission"
                else -> "Re-run checks"
            },
        )
    }

    /** Before anything has been asked, and while Google Play is still answering. */
    fun checking(runCount: Int, stamp: String) = DeviceReport(
        runCount = runCount,
        stamp = stamp,
        verdictHeadline = "Checking",
        verdictDetail = "Asking Google Play what this phone supports.",
        verdictColour = MeasureColours.Idle,
        checks = emptyList(),
        device = deviceLines(),
        core = emptyList(),
        crash = null,
        canMeasure = false,
        actionLabel = "Re-run checks",
    )

    /** The check itself threw, which is not the same as an unsupported device. */
    fun failed(runCount: Int, stamp: String, message: String, crash: String?) = DeviceReport(
        runCount = runCount,
        stamp = stamp,
        verdictHeadline = "The check itself failed",
        verdictDetail = "ARCore could not be asked whether it supports this phone. That is " +
            "not the same as an unsupported phone, and re-running may well succeed.",
        verdictColour = MeasureColours.Blocked,
        checks = listOf(CheckLine("Error", message, MeasureColours.Blocked)),
        device = deviceLines(),
        core = coreLines(),
        crash = crash,
        canMeasure = false,
        actionLabel = "Re-run checks",
    )

    private data class VerdictText(val headline: String, val detail: String, val colour: Color)

    private fun verdict(
        availability: ArCoreApk.Availability,
        depth: DepthSupport?,
    ): VerdictText = when {
        availability == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> VerdictText(
            headline = "This phone cannot measure",
            detail = "ARCore does not support this device, so measuring by camera is not " +
                "possible here. Saved plans can still be opened and sent.",
            colour = MeasureColours.Blocked,
        )

        availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> VerdictText(
            headline = "One install away",
            detail = "This phone is supported, but Google Play Services for AR is not " +
                "installed yet.",
            colour = MeasureColours.Warning,
        )

        availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> VerdictText(
            headline = "One update away",
            detail = "This phone is supported, but Google Play Services for AR needs " +
                "updating.",
            colour = MeasureColours.Warning,
        )

        depth?.error != null -> VerdictText(
            headline = "ARCore would not start",
            detail = "The device reports support, but opening a session failed. The reason " +
                "is below; it is usually a permission or an ARCore update in progress.",
            colour = MeasureColours.Blocked,
        )

        depth?.automatic == true -> VerdictText(
            headline = "Fully supported",
            detail = "ARCore and the Depth API are both available. Everything this app can " +
                "do, it can do on this phone.",
            colour = MeasureColours.Ready,
        )

        depth != null -> VerdictText(
            headline = "Supported, without depth",
            detail = "ARCore works but the Depth API is not available, so measuring falls " +
                "back to detected planes. Corners away from a floor or a wall will be less " +
                "accurate.",
            colour = MeasureColours.Warning,
        )

        else -> VerdictText(
            headline = "Camera access needed",
            detail = "ARCore is supported. Grant camera access to test the Depth API.",
            colour = MeasureColours.Warning,
        )
    }

    private fun checkLines(
        availability: ArCoreApk.Availability,
        hasCamera: Boolean,
        depth: DepthSupport?,
    ): List<CheckLine> = buildList {
        add(
            CheckLine(
                label = "ARCore",
                value = describe(availability),
                colour = when (availability) {
                    ArCoreApk.Availability.SUPPORTED_INSTALLED -> MeasureColours.Ready
                    ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> MeasureColours.Blocked
                    else -> MeasureColours.Warning
                },
            ),
        )
        add(
            CheckLine(
                label = "Camera permission",
                value = if (hasCamera) "granted" else "not granted",
                colour = if (hasCamera) MeasureColours.Ready else MeasureColours.Blocked,
            ),
        )
        when {
            depth == null && !hasCamera ->
                add(CheckLine("Depth API", "needs camera access", MeasureColours.Warning))

            depth == null ->
                add(CheckLine("Depth API", "needs ARCore", MeasureColours.Warning))

            depth.error != null -> {
                add(CheckLine("Depth API", "session failed", MeasureColours.Blocked))
                add(CheckLine("Reason", depth.error, MeasureColours.Blocked))
            }

            else -> {
                add(
                    CheckLine(
                        label = "Depth API",
                        value = yesNo(depth.automatic),
                        colour = if (depth.automatic) MeasureColours.Ready else MeasureColours.Warning,
                    ),
                )
                add(
                    CheckLine(
                        label = "Raw depth",
                        value = yesNo(depth.raw),
                        colour = if (depth.raw) MeasureColours.Ready else MeasureColours.OnScrimMuted,
                    ),
                )
            }
        }
    }

    private fun deviceLines(): List<String> = listOf(
        "${Build.MANUFACTURER} ${Build.MODEL}",
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        Build.SUPPORTED_ABIS.joinToString(", "),
    )

    /**
     * Runs the pure-Kotlin measurement core on the handset, on a synthetic room with the
     * same noise and drift used in the JVM tests. It proves the core is wired in and
     * behaves identically on ARM as it does in CI.
     */
    private fun coreLines(): List<CheckLine> = try {
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

        buildList {
            solution.polygon.edges.forEachIndexed { index, edge ->
                add(
                    CheckLine(
                        label = "Wall ${index + 1}",
                        value = LengthFormatter.formatMetric(Length(edge.length)),
                        colour = MeasureColours.OnScrim,
                    ),
                )
            }
            add(
                CheckLine(
                    label = "Area, true 20 m²",
                    value = AreaFormatter.format(solution.area, UnitSystem.METRIC),
                    colour = MeasureColours.OnScrim,
                ),
            )
            add(
                CheckLine(
                    label = "Misclosure",
                    value = String.format(
                        Locale.getDefault(), "%.1f%%", solution.closure.relativeError * 100,
                    ),
                    colour = MeasureColours.OnScrimMuted,
                ),
            )
            add(
                CheckLine(
                    label = "Reliable",
                    value = yesNo(solution.isReliable),
                    colour = if (solution.isReliable) MeasureColours.Ready else MeasureColours.Warning,
                ),
            )
        }
    } catch (error: Throwable) {
        listOf(
            CheckLine(
                label = "Core failed",
                value = "${error.javaClass.simpleName}: ${error.message}",
                colour = MeasureColours.Blocked,
            ),
        )
    }

    private fun describe(availability: ArCoreApk.Availability): String = when (availability) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> "installed"
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> "not installed"
        ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> "needs updating"
        ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> "not supported"
        ArCoreApk.Availability.UNKNOWN_CHECKING -> "checking"
        ArCoreApk.Availability.UNKNOWN_ERROR -> "unknown (Play error)"
        ArCoreApk.Availability.UNKNOWN_TIMED_OUT -> "unknown (timed out)"
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"
}
