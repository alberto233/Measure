package com.measure.feature.export

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.measure.core.designsystem.MeasureColours
import com.measure.core.designsystem.touchTarget
import com.measure.core.export.ExportFormat

/**
 * The formats a plan can leave in.
 *
 * Each says what it is *for* rather than only what it is called. "DXF" means nothing to
 * most people and everything to a joiner, and the difference between choosing right and
 * choosing at random is one line of text.
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
            .padding(16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MeasureColours.Panel)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Send this plan as",
                color = MeasureColours.OnScrim,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MeasureColours.ScrimSoft)
                    .clickable(onClick = onDismiss)
                    .touchTarget()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "Cancel", color = MeasureColours.OnScrim, fontSize = 13.sp)
            }
        }

        ExportFormat.entries.forEach { format ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onExport(format) }
                    .touchTarget()
                    .padding(vertical = 10.dp, horizontal = 4.dp),
            ) {
                Text(
                    text = format.label,
                    color = MeasureColours.OnScrim,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = format.description,
                    color = MeasureColours.OnScrimMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
