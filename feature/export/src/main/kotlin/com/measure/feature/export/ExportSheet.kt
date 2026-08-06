package com.measure.feature.export

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.measure.core.designsystem.MeasureButton
import com.measure.core.designsystem.MeasureColours
import com.measure.core.designsystem.MeasureRule
import com.measure.core.designsystem.MeasureShape
import com.measure.core.designsystem.MeasureSpace
import com.measure.core.designsystem.MeasureTag
import com.measure.core.designsystem.MeasureType
import com.measure.core.designsystem.touchTarget
import com.measure.core.export.ExportFormat

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
                MeasureTag("send as")
                Text(
                    text = "Choose a format",
                    color = MeasureColours.OnScrim,
                    style = MeasureType.Title,
                )
            }
            MeasureButton("Cancel", onClick = onDismiss)
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
                Text(format.label, color = MeasureColours.OnScrim, style = MeasureType.Label)
                Text(
                    text = format.description,
                    color = MeasureColours.OnScrimMuted,
                    style = MeasureType.Small,
                )
            }
            MeasureRule()
        }
    }
}
