package com.measure.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.measure.core.designsystem.MeasureButton
import com.measure.core.designsystem.MeasureColours
import com.measure.core.designsystem.MeasureField
import com.measure.core.designsystem.MeasurePrimaryButton
import com.measure.core.designsystem.MeasureRule
import com.measure.core.designsystem.MeasureShape
import com.measure.core.designsystem.MeasureSpace
import com.measure.core.designsystem.MeasureTag
import com.measure.core.designsystem.MeasureType
import com.measure.core.geometry.capture.Calibration
import kotlin.math.abs

/** Matches the hairline the design system uses; it is not exported as a token. */
private val Hairline = 1.dp

/**
 * What this phone can do, as a screen rather than as a paragraph.
 *
 * The report used to be one monospaced `TextView` holding everything the checks produced,
 * with the answer — the line beginning "VERDICT:" — somewhere around line fourteen. That is
 * a log, and a log is the right shape for a developer reading a failure and the wrong shape
 * for the person this screen is actually for, who has one question: *can I measure with
 * this phone, and how well*.
 *
 * So the answer is first and it is the largest thing on the screen. Underneath it are the
 * individual checks it was derived from, each as a row that can be scanned rather than
 * read, and under those the identity of the handset and the self-test of the measurement
 * core. The crash report keeps its monospace, because a stack trace is genuinely a log and
 * lining up is what makes it readable.
 *
 * **The state colours are used here deliberately.** `docs/PRODUCT_PLAN.md` M10a says
 * `Ready`, `Warning` and `Blocked` mean "this is how good the measurement is" and that
 * nothing decorative may borrow them. This screen is not decoration: it is the earliest
 * statement of exactly that, made about the device instead of about a single measurement.
 */
@Composable
fun DeviceCheckScreen(
    report: DeviceReport,
    onStartMeasuring: () -> Unit,
    onPrimaryCheckAction: () -> Unit,
    onClearCrash: () -> Unit,
    modifier: Modifier = Modifier,
    calibration: Calibration = Calibration.NONE,
    onCalibrate: (measured: String, actual: String) -> Unit = { _, _ -> },
    onClearCalibration: () -> Unit = {},
    calibrationNote: String? = null,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(MeasureColours.Surface)
            .safeDrawingPadding(),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(MeasureSpace.Loose),
            verticalArrangement = Arrangement.spacedBy(MeasureSpace.Snug),
        ) {
            MeasureTag(stringResource(R.string.device_check_tag))
            Text(
                text = stringResource(R.string.device_check_heading),
                color = MeasureColours.Ink,
                style = MeasureType.Display,
            )

            report.crash?.let { CrashReport(it) }

            Verdict(report)

            MeasureRule()

            MeasureTag(stringResource(R.string.device_check_tag_checks))
            report.checks.forEach { CheckRow(it) }

            MeasureRule()

            MeasureTag(stringResource(R.string.device_check_tag_device))
            report.device.forEach {
                Text(it, color = MeasureColours.InkMuted, style = MeasureType.Body)
            }

            MeasureRule()

            // The measurement core, run on the handset against the same synthetic room the
            // JVM tests use. It proves the maths is wired in and behaves identically on ARM
            // as it does in CI, which is the one thing a device cannot be asked about.
            MeasureTag(stringResource(R.string.device_check_tag_core))
            Text(
                text = stringResource(R.string.device_check_core_note),
                color = MeasureColours.InkMuted,
                style = MeasureType.Small,
            )
            report.core.forEach { CheckRow(it) }

            MeasureRule()

            CalibrationSection(
                calibration = calibration,
                note = calibrationNote,
                onCalibrate = onCalibrate,
                onClear = onClearCalibration,
            )

            Text(
                text = stringResource(
                    R.string.device_check_run,
                    report.runCount,
                    report.stamp,
                ),
                color = MeasureColours.InkMuted,
                style = MeasureType.Small,
            )
        }

        // A fixed bar rather than the end of the scroll region.
        //
        // The buttons used to sit inside the scroll, which put the primary action above a
        // report long enough to need scrolling: it slid under the top of the screen and
        // became unreachable. An action that is always available should always be visible,
        // and the bottom of the screen is where a thumb already is.
        MeasureRule()
        Column(
            Modifier.fillMaxWidth().padding(MeasureSpace.Loose),
            verticalArrangement = Arrangement.spacedBy(MeasureSpace.Tight),
        ) {
            MeasurePrimaryButton(
                label = stringResource(
                    if (report.canMeasure) {
                        R.string.device_check_start
                    } else {
                        R.string.device_check_cannot_start
                    },
                ),
                onClick = onStartMeasuring,
                enabled = report.canMeasure,
            )
            MeasureButton(
                label = report.actionLabel,
                onClick = onPrimaryCheckAction,
                modifier = Modifier.fillMaxWidth(),
            )
            if (report.crash != null) {
                MeasureButton(
                    label = stringResource(R.string.device_check_clear_crash),
                    onClick = onClearCrash,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The optional scale correction — `docs/ACCURACY.md` M9.
 *
 * **On this screen and not in a settings menu.** A calibration is a fact about the handset,
 * and this is the screen that exists to say what the handset can do. It also has the right
 * audience: nobody arrives here by accident, and the person who has come to find out why
 * their measurements look off is exactly the person this is for.
 *
 * **Two typed figures rather than a guided measuring flow.** That is how somebody actually
 * discovers a bias — they measure a door they know, see 2.09 m, and go looking for the
 * setting. Sending them back to measure the same door again inside a special mode would add
 * navigation and learn nothing new.
 *
 * The two sentences under the state are the important part of the whole feature. One says the
 * correction is not retroactive; the other says it removes bias and not spread. Without them
 * "calibrated" reads as "now it is exact", which is the overclaim this product is built
 * against.
 */
@Composable
private fun CalibrationSection(
    calibration: Calibration,
    note: String?,
    onCalibrate: (String, String) -> Unit,
    onClear: () -> Unit,
) {
    var measured by rememberSaveable { mutableStateOf("") }
    var actual by rememberSaveable { mutableStateOf("") }

    MeasureTag(stringResource(R.string.calibration_tag))
    Text(
        text = stringResource(R.string.calibration_title),
        color = MeasureColours.Ink,
        style = MeasureType.Title,
    )

    Text(
        text = when {
            calibration.isIdentity -> stringResource(R.string.calibration_none)
            calibration.bias > 0 -> stringResource(
                R.string.calibration_long,
                stringResource(R.string.calibration_percent, abs(calibration.bias) * 100),
            )
            else -> stringResource(
                R.string.calibration_short,
                stringResource(R.string.calibration_percent, abs(calibration.bias) * 100),
            )
        },
        color = if (calibration.isIdentity) MeasureColours.InkMuted else MeasureColours.Ink,
        style = MeasureType.Body,
    )

    Text(
        text = stringResource(R.string.calibration_help),
        color = MeasureColours.InkMuted,
        style = MeasureType.Small,
    )

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MeasureSpace.Tight),
    ) {
        MeasureField(
            value = measured,
            onValueChange = { measured = it },
            hint = stringResource(R.string.calibration_measured_hint),
            numeric = true,
            modifier = Modifier.weight(1f),
        )
        MeasureField(
            value = actual,
            onValueChange = { actual = it },
            hint = stringResource(R.string.calibration_actual_hint),
            numeric = true,
            modifier = Modifier.weight(1f),
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(MeasureSpace.Tight)) {
        MeasureButton(
            label = stringResource(R.string.calibration_apply),
            onClick = { onCalibrate(measured, actual) },
            enabled = measured.isNotBlank() && actual.isNotBlank(),
        )
        if (!calibration.isIdentity) {
            MeasureButton(
                label = stringResource(R.string.calibration_clear),
                onClick = {
                    measured = ""
                    actual = ""
                    onClear()
                },
            )
        }
    }

    note?.let {
        Text(text = it, color = MeasureColours.Ink, style = MeasureType.Small)
    }

    Text(
        text = stringResource(R.string.calibration_not_retroactive),
        color = MeasureColours.InkMuted,
        style = MeasureType.Small,
    )
    Text(
        text = stringResource(R.string.calibration_still_approximate),
        color = MeasureColours.InkMuted,
        style = MeasureType.Small,
    )
}

/** The answer, at the size of an answer. */
@Composable
private fun Verdict(report: DeviceReport) {
    val shape = RoundedCornerShape(MeasureShape.Panel)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MeasureColours.Panel, shape)
            .border(Hairline, report.verdictColour, shape)
            .padding(MeasureSpace.Base),
        verticalArrangement = Arrangement.spacedBy(MeasureSpace.Hair),
    ) {
        MeasureTag(stringResource(R.string.device_check_tag_verdict), colour = report.verdictColour)
        Text(report.verdictHeadline, color = report.verdictColour, style = MeasureType.Title)
        Text(report.verdictDetail, color = MeasureColours.InkMuted, style = MeasureType.Body)
    }
}

/**
 * One check: what was asked on the left, what came back on the right.
 *
 * Two columns rather than "Label: value" in a sentence, because the point of a list of
 * checks is that the answers line up and the one that is not green is found without
 * reading any of the others.
 */
@Composable
private fun CheckRow(row: CheckLine) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = MeasureSpace.Hair),
        horizontalArrangement = Arrangement.spacedBy(MeasureSpace.Snug),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = row.label,
            color = MeasureColours.InkMuted,
            style = MeasureType.Label,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = row.value,
            color = row.colour,
            style = MeasureType.ValueSmall,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The last crash, in full and in monospace.
 *
 * First on the screen, above even the verdict. On a sideloaded build there is no `adb
 * logcat` to attach, so this is the only place a stack trace can be read at all, and a
 * crash the user has just seen is a more urgent fact than whether Depth is supported.
 *
 * Scrolls sideways rather than wrapping: a wrapped stack trace loses the alignment that
 * makes frames scannable, and the interesting part of a frame is its start.
 */
@Composable
private fun CrashReport(crash: String) {
    val shape = RoundedCornerShape(MeasureShape.Panel)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MeasureColours.Panel, shape)
            .border(Hairline, MeasureColours.Blocked, shape)
            .padding(MeasureSpace.Base),
        verticalArrangement = Arrangement.spacedBy(MeasureSpace.Tight),
    ) {
        MeasureTag(stringResource(R.string.device_check_crashed), colour = MeasureColours.Blocked)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Text(crash.trimEnd(), color = MeasureColours.InkMuted, style = MeasureType.ValueSmall)
        }
    }
}

/** One row of the report: a question and its answer, coloured by how good the answer is. */
data class CheckLine(val label: String, val value: String, val colour: Color)

/**
 * Everything the checks produced, already reduced to what the screen shows.
 *
 * A rendered report rather than raw results, so that deciding *what the answer means* —
 * which availability values are fatal, which are merely awkward — happens once, in
 * `MainActivity`, where it can be read next to the ARCore calls it interprets.
 */
data class DeviceReport(
    val runCount: Int,
    val stamp: String,
    val verdictHeadline: String,
    val verdictDetail: String,
    val verdictColour: Color,
    val checks: List<CheckLine>,
    val device: List<String>,
    val core: List<CheckLine>,
    val crash: String?,
    val canMeasure: Boolean,
    val actionLabel: String,
)
