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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.measure.core.designsystem.MeasureButton
import com.measure.core.designsystem.MeasureColours
import com.measure.core.designsystem.MeasureRule
import com.measure.core.designsystem.MeasureShape
import com.measure.core.designsystem.MeasureSpace
import com.measure.core.designsystem.MeasureTag
import com.measure.core.designsystem.MeasureType

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
            MeasureTag("Device capability")
            Text("What this phone can do", color = MeasureColours.OnScrim, style = MeasureType.Display)

            report.crash?.let { CrashReport(it) }

            Verdict(report)

            MeasureRule()

            MeasureTag("Checks")
            report.checks.forEach { CheckRow(it) }

            MeasureRule()

            MeasureTag("Device")
            report.device.forEach {
                Text(it, color = MeasureColours.OnScrimMuted, style = MeasureType.Body)
            }

            MeasureRule()

            // The measurement core, run on the handset against the same synthetic room the
            // JVM tests use. It proves the maths is wired in and behaves identically on ARM
            // as it does in CI, which is the one thing a device cannot be asked about.
            MeasureTag("Measurement core")
            Text(
                text = "A synthetic 5.00 × 4.00 m room, solved here rather than in CI",
                color = MeasureColours.OnScrimMuted,
                style = MeasureType.Small,
            )
            report.core.forEach { CheckRow(it) }

            Text(
                text = "Check ${report.runCount} at ${report.stamp}",
                color = MeasureColours.OnScrimMuted,
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
            MeasureButton(
                label = if (report.canMeasure) "Start measuring" else "Measuring unavailable",
                onClick = onStartMeasuring,
                modifier = Modifier.fillMaxWidth(),
                primary = true,
                enabled = report.canMeasure,
            )
            MeasureButton(
                label = report.actionLabel,
                onClick = onPrimaryCheckAction,
                modifier = Modifier.fillMaxWidth(),
            )
            if (report.crash != null) {
                MeasureButton(
                    label = "Clear crash report",
                    onClick = onClearCrash,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
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
        MeasureTag("Verdict", colour = report.verdictColour)
        Text(report.verdictHeadline, color = report.verdictColour, style = MeasureType.Title)
        Text(report.verdictDetail, color = MeasureColours.OnScrimMuted, style = MeasureType.Body)
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
            color = MeasureColours.OnScrimMuted,
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
        MeasureTag("Previous run crashed", colour = MeasureColours.Blocked)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Text(crash.trimEnd(), color = MeasureColours.OnScrimMuted, style = MeasureType.ValueSmall)
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
