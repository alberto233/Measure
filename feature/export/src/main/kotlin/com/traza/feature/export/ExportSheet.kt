package com.traza.feature.export

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import com.traza.core.designsystem.MeasureButton
import com.traza.core.designsystem.MeasureColours
import com.traza.core.designsystem.MeasureRule
import com.traza.core.designsystem.MeasureShape
import com.traza.core.designsystem.MeasureSpace
import com.traza.core.designsystem.MeasureTag
import com.traza.core.designsystem.MeasureType
import com.traza.core.designsystem.touchTarget
import com.traza.core.export.ExportFormat

/**
 * The formats a plan can leave in — docs/PRODUCT_PLAN.md M10a, direction A.
 *
 * Each says what it is *for* rather than only what it is called. "DXF" means nothing to
 * most people and everything to a joiner, and the difference between choosing right and
 * choosing at random is one line of text.
 *
 * Ruled entries rather than tinted rows, for the same reason the plan list uses them: on a
 * dark ground a stack of filled boxes reads as one grey mass, and what wants to be legible
 * here is six names and six sentences.
 */
@Composable
fun ExportSheet(
    onExport: (ExportFormat) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(MeasureSpace.Base)
            .clip(RoundedCornerShape(MeasureShape.Panel))
            .background(MeasureColours.Panel)
            .padding(MeasureSpace.Loose),
        verticalArrangement = Arrangement.spacedBy(MeasureSpace.Tight),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                MeasureTag(stringResource(R.string.export_tag))
                Text(
                    text = stringResource(R.string.export_title),
                    color = MeasureColours.Ink,
                    style = MeasureType.Title,
                )
            }
            MeasureButton(stringResource(R.string.export_cancel), onClick = onDismiss)
        }

        MeasureRule()

        ExportFormat.entries.forEach { format ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onExport(format) }
                    .touchTarget()
                    .padding(vertical = MeasureSpace.Snug),
                verticalArrangement = Arrangement.spacedBy(MeasureSpace.Hair),
            ) {
                Text(
                    text = stringResource(format.label()),
                    color = MeasureColours.Ink,
                    style = MeasureType.Label,
                )
                Text(
                    text = stringResource(format.detail()),
                    color = MeasureColours.InkMuted,
                    style = MeasureType.Small,
                )
            }
            MeasureRule()
        }
    }
}

/**
 * A format's name and its one-line explanation, mapped here rather than on the enum.
 *
 * `ExportFormat` lives in `:core:export`, which is pure Kotlin: it can own a file extension
 * and a mime type, because those are facts about the format, but it cannot own a sentence
 * that has to arrive in the reader's language.
 */
@StringRes
private fun ExportFormat.label(): Int = when (this) {
    ExportFormat.PDF -> R.string.export_format_pdf
    ExportFormat.PNG -> R.string.export_format_png
    ExportFormat.SVG -> R.string.export_format_svg
    ExportFormat.DXF -> R.string.export_format_dxf
    ExportFormat.CSV -> R.string.export_format_csv
    ExportFormat.JSON -> R.string.export_format_json
}

@StringRes
private fun ExportFormat.detail(): Int = when (this) {
    ExportFormat.PDF -> R.string.export_format_pdf_detail
    ExportFormat.PNG -> R.string.export_format_png_detail
    ExportFormat.SVG -> R.string.export_format_svg_detail
    ExportFormat.DXF -> R.string.export_format_dxf_detail
    ExportFormat.CSV -> R.string.export_format_csv_detail
    ExportFormat.JSON -> R.string.export_format_json_detail
}
