package com.measure.feature.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.measure.core.data.ProjectSummary
import com.measure.core.designsystem.MeasureColours
import com.measure.core.designsystem.PlanStyle
import com.measure.core.designsystem.PlanView
import com.measure.core.units.AreaFormatter
import java.text.DateFormat
import java.util.Date

/**
 * The home screen: everything measured so far, newest first.
 *
 * There is no capability gate in front of this. There used to be one in front of the
 * capture screen, on the reasoning that opening an AR session we know will fail is how
 * competitors earn their crash-on-scan-start reviews — but the capture screen now reports
 * every ARCore failure itself, with a sentence and a way out, so gating the *list* of
 * saved work behind a camera check would only stop people reading plans they already have.
 * The device report is still one tap away.
 */
@Composable
fun ProjectsScreen(
    onNewMeasurement: () -> Unit,
    onOpenProject: (Long) -> Unit,
    onDeviceCheck: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProjectsViewModel = viewModel(),
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }

    Box(
        modifier
            .fillMaxSize()
            .background(MeasureColours.Surface)
            .safeDrawingPadding(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header(onDeviceCheck) }

            when {
                projects == null -> Unit // Loading: say nothing rather than say "empty".
                projects!!.isEmpty() -> item { EmptyState() }
                else -> items(projects!!, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        onOpen = { onOpenProject(project.id) },
                        onRename = { renaming = project },
                        onDelete = { deleting = project },
                    )
                }
            }
        }

        NewMeasurementButton(
            onClick = onNewMeasurement,
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
        )
    }

    renaming?.let { project ->
        RenameDialog(
            initial = project.name,
            onDismiss = { renaming = null },
            onConfirm = {
                viewModel.rename(project.id, it)
                renaming = null
            },
        )
    }

    deleting?.let { project ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${project.name}?") },
            text = { Text("Its rooms and measurements go with it. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(project.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun Header(onDeviceCheck: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Plans",
            color = MeasureColours.OnScrim,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Device check",
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onDeviceCheck)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            color = MeasureColours.OnScrimMuted,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Nothing measured yet",
            color = MeasureColours.OnScrim,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Point the camera at a room and walk the corners.\nEverything saves as you go.",
            color = MeasureColours.OnScrimMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One saved plan.
 *
 * The thumbnail is *drawn* from the stored corners rather than being a saved image. It
 * cannot go stale, there are no files to clean up when a project is deleted, and it is
 * the same drawing code as the live minimap — so a room looks the same here as it did
 * while it was being captured.
 */
@Composable
private fun ProjectCard(
    project: ProjectSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MeasureColours.Scrim)
            .clickable(onClick = onOpen)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MeasureColours.ScrimSoft),
        ) {
            PlanView(
                outlines = project.outlines,
                modifier = Modifier.fillMaxSize(),
                style = PlanStyle(
                    strokeWidth = 1.8f,
                    filled = true,
                    showVertices = false,
                    paddingDp = 10f,
                ),
            )
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = project.name,
                color = MeasureColours.OnScrim,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = project.describeContents(),
                color = MeasureColours.OnScrimMuted,
                fontSize = 13.sp,
            )
            Text(
                text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(project.updatedAt)),
                color = MeasureColours.OnScrimMuted.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CardAction("Rename", onRename)
            CardAction("Delete", onDelete)
        }
    }
}

private fun ProjectSummary.describeContents(): String {
    if (isEmpty) return "Empty"
    val parts = buildList {
        if (roomCount > 0) {
            add("$roomCount ${if (roomCount == 1) "room" else "rooms"}")
            add(AreaFormatter.format(totalArea, unitSystem))
        }
        if (measurementCount > 0) {
            add("$measurementCount ${if (measurementCount == 1) "measurement" else "measurements"}")
        }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun CardAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MeasureColours.ScrimSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = MeasureColours.OnScrimMuted,
        fontSize = 12.sp,
    )
}

@Composable
private fun NewMeasurementButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = "New measurement",
        modifier = modifier
            .clip(CircleShape)
            .background(MeasureColours.Ready)
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 16.dp),
        color = Color(0xFF06231F),
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename plan") },
        text = {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = MeasureColours.OnScrim, fontSize = 16.sp),
                cursorBrush = SolidColor(MeasureColours.Ready),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MeasureColours.ScrimSoft)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
