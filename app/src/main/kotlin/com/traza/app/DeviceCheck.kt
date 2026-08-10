package com.traza.app

import android.content.res.Resources
import android.os.Build
import androidx.compose.ui.graphics.Color
import com.google.ar.core.ArCoreApk
import com.traza.core.designsystem.MeasureColours
import com.traza.core.geometry.CapturedCorner
import com.traza.core.geometry.RoomCapture
import com.traza.core.geometry.RoomSolver
import com.traza.core.geometry.Vec2
import com.traza.core.units.AreaFormatter
import com.traza.core.units.Length
import com.traza.core.units.LengthFormatter
import com.traza.core.units.UnitSystem
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
 *
 * **Every entry point takes `Resources`.** The verdicts are prose, and prose is translated;
 * an object that decided what to say without being able to say it in the user's language
 * would have to hand back identifiers for the screen to resolve, which is the same coupling
 * with an extra indirection. The screenshot tests pass Robolectric's, which is also how the
 * Spanish renders in `DeviceCheckScreenshotTest` are taken.
 */
object DeviceCheck {

    /** The full report, once the availability check has settled. */
    fun of(
        resources: Resources,
        runCount: Int,
        stamp: String,
        availability: ArCoreApk.Availability,
        hasCamera: Boolean,
        depth: DepthSupport?,
        crash: String?,
    ): DeviceReport {
        val verdict = verdict(resources, availability, depth)
        val needsArCore = availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ||
            availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD

        return DeviceReport(
            runCount = runCount,
            stamp = stamp,
            verdictHeadline = verdict.headline,
            verdictDetail = verdict.detail,
            verdictColour = verdict.colour,
            checks = checkLines(resources, availability, hasCamera, depth),
            device = deviceLines(resources),
            core = coreLines(resources),
            crash = crash,
            // Capture is offered only once the device has actually proved it can do it.
            // Opening an AR session we already know will fail is how competitors produce
            // the crash-on-scan-start reviews in docs/PRODUCT_PLAN.md §4.
            canMeasure = availability == ArCoreApk.Availability.SUPPORTED_INSTALLED && hasCamera,
            actionLabel = resources.getString(
                when {
                    needsArCore -> R.string.device_check_action_install_arcore
                    !hasCamera -> R.string.device_check_action_grant_camera
                    else -> R.string.device_check_action_rerun
                },
            ),
        )
    }

    /** Before anything has been asked, and while Google Play is still answering. */
    fun checking(resources: Resources, runCount: Int, stamp: String) = DeviceReport(
        runCount = runCount,
        stamp = stamp,
        verdictHeadline = resources.getString(R.string.device_check_checking),
        verdictDetail = resources.getString(R.string.device_check_checking_detail),
        verdictColour = MeasureColours.InkMuted,
        checks = emptyList(),
        device = deviceLines(resources),
        core = emptyList(),
        crash = null,
        canMeasure = false,
        actionLabel = resources.getString(R.string.device_check_action_rerun),
    )

    /** The check itself threw, which is not the same as an unsupported device. */
    fun failed(
        resources: Resources,
        runCount: Int,
        stamp: String,
        message: String,
        crash: String?,
    ) = DeviceReport(
        runCount = runCount,
        stamp = stamp,
        verdictHeadline = resources.getString(R.string.device_check_failed),
        verdictDetail = resources.getString(R.string.device_check_failed_detail),
        verdictColour = MeasureColours.Blocked,
        checks = listOf(
            CheckLine(
                resources.getString(R.string.device_check_row_error),
                message,
                MeasureColours.Blocked,
            ),
        ),
        device = deviceLines(resources),
        core = coreLines(resources),
        crash = crash,
        canMeasure = false,
        actionLabel = resources.getString(R.string.device_check_action_rerun),
    )

    private data class VerdictText(val headline: String, val detail: String, val colour: Color)

    private fun verdict(
        resources: Resources,
        availability: ArCoreApk.Availability,
        depth: DepthSupport?,
    ): VerdictText = when {
        availability == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> VerdictText(
            headline = resources.getString(R.string.device_check_unsupported),
            detail = resources.getString(R.string.device_check_unsupported_detail),
            colour = MeasureColours.Blocked,
        )

        availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> VerdictText(
            headline = resources.getString(R.string.device_check_needs_install),
            detail = resources.getString(R.string.device_check_needs_install_detail),
            colour = MeasureColours.Warning,
        )

        availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> VerdictText(
            headline = resources.getString(R.string.device_check_needs_update),
            detail = resources.getString(R.string.device_check_needs_update_detail),
            colour = MeasureColours.Warning,
        )

        depth?.error != null -> VerdictText(
            headline = resources.getString(R.string.device_check_session_failed),
            detail = resources.getString(R.string.device_check_session_failed_detail),
            colour = MeasureColours.Blocked,
        )

        depth?.automatic == true -> VerdictText(
            headline = resources.getString(R.string.device_check_full),
            detail = resources.getString(R.string.device_check_full_detail),
            colour = MeasureColours.Ready,
        )

        depth != null -> VerdictText(
            headline = resources.getString(R.string.device_check_no_depth),
            detail = resources.getString(R.string.device_check_no_depth_detail),
            colour = MeasureColours.Warning,
        )

        else -> VerdictText(
            headline = resources.getString(R.string.device_check_needs_camera),
            detail = resources.getString(R.string.device_check_needs_camera_detail),
            colour = MeasureColours.Warning,
        )
    }

    private fun checkLines(
        resources: Resources,
        availability: ArCoreApk.Availability,
        hasCamera: Boolean,
        depth: DepthSupport?,
    ): List<CheckLine> = buildList {
        add(
            CheckLine(
                label = resources.getString(R.string.device_check_row_arcore),
                value = describe(resources, availability),
                colour = when (availability) {
                    ArCoreApk.Availability.SUPPORTED_INSTALLED -> MeasureColours.Ready
                    ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> MeasureColours.Blocked
                    else -> MeasureColours.Warning
                },
            ),
        )
        add(
            CheckLine(
                label = resources.getString(R.string.device_check_row_camera),
                value = resources.getString(
                    if (hasCamera) R.string.device_check_granted else R.string.device_check_not_granted,
                ),
                colour = if (hasCamera) MeasureColours.Ready else MeasureColours.Blocked,
            ),
        )
        when {
            depth == null && !hasCamera -> add(
                CheckLine(
                    resources.getString(R.string.device_check_row_depth),
                    resources.getString(R.string.device_check_needs_camera_short),
                    MeasureColours.Warning,
                ),
            )

            depth == null -> add(
                CheckLine(
                    resources.getString(R.string.device_check_row_depth),
                    resources.getString(R.string.device_check_needs_arcore_short),
                    MeasureColours.Warning,
                ),
            )

            depth.error != null -> {
                add(
                    CheckLine(
                        resources.getString(R.string.device_check_row_depth),
                        resources.getString(R.string.device_check_session_failed_short),
                        MeasureColours.Blocked,
                    ),
                )
                add(
                    CheckLine(
                        resources.getString(R.string.device_check_row_reason),
                        depth.error,
                        MeasureColours.Blocked,
                    ),
                )
            }

            else -> {
                add(
                    CheckLine(
                        label = resources.getString(R.string.device_check_row_depth),
                        value = yesNo(resources, depth.automatic),
                        colour = if (depth.automatic) MeasureColours.Ready else MeasureColours.Warning,
                    ),
                )
                add(
                    CheckLine(
                        label = resources.getString(R.string.device_check_row_raw_depth),
                        value = yesNo(resources, depth.raw),
                        colour = if (depth.raw) MeasureColours.Ready else MeasureColours.InkMuted,
                    ),
                )
            }
        }
    }

    private fun deviceLines(resources: Resources): List<String> = listOf(
        "${Build.MANUFACTURER} ${Build.MODEL}",
        resources.getString(
            R.string.device_check_android_version,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT,
        ),
        Build.SUPPORTED_ABIS.joinToString(", "),
    )

    /**
     * Runs the pure-Kotlin measurement core on the handset, on a synthetic room with the
     * same noise and drift used in the JVM tests. It proves the core is wired in and
     * behaves identically on ARM as it does in CI.
     */
    private fun coreLines(resources: Resources): List<CheckLine> = try {
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
                        label = resources.getString(R.string.device_check_row_wall, index + 1),
                        value = LengthFormatter.formatMetric(Length(edge.length)),
                        colour = MeasureColours.Ink,
                    ),
                )
            }
            add(
                CheckLine(
                    label = resources.getString(R.string.device_check_row_area),
                    value = AreaFormatter.format(solution.area, UnitSystem.METRIC),
                    colour = MeasureColours.Ink,
                ),
            )
            add(
                CheckLine(
                    label = resources.getString(R.string.device_check_row_misclosure),
                    value = String.format(
                        Locale.getDefault(), "%.1f%%", solution.closure.relativeError * 100,
                    ),
                    colour = MeasureColours.InkMuted,
                ),
            )
            add(
                CheckLine(
                    label = resources.getString(R.string.device_check_row_reliable),
                    value = yesNo(resources, solution.isReliable),
                    colour = if (solution.isReliable) MeasureColours.Ready else MeasureColours.Warning,
                ),
            )
        }
    } catch (error: Throwable) {
        listOf(
            CheckLine(
                label = resources.getString(R.string.device_check_row_core_failed),
                value = "${error.javaClass.simpleName}: ${error.message}",
                colour = MeasureColours.Blocked,
            ),
        )
    }

    private fun describe(
        resources: Resources,
        availability: ArCoreApk.Availability,
    ): String = resources.getString(
        when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> R.string.device_check_arcore_installed
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> R.string.device_check_arcore_not_installed
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> R.string.device_check_arcore_needs_update
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> R.string.device_check_arcore_unsupported
            ArCoreApk.Availability.UNKNOWN_CHECKING -> R.string.device_check_arcore_checking
            ArCoreApk.Availability.UNKNOWN_ERROR -> R.string.device_check_arcore_play_error
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT -> R.string.device_check_arcore_timed_out
        },
    )

    private fun yesNo(resources: Resources, value: Boolean): String =
        resources.getString(if (value) R.string.device_check_yes else R.string.device_check_no)
}
