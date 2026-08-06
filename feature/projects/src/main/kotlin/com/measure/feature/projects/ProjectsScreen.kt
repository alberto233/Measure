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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.measure.core.data.ProjectSort
import com.measure.core.data.ProjectSummary
import com.measure.core.designsystem.MeasureColours
import com.measure.core.designsystem.PlanStyle
import com.measure.core.designsystem.PlanView
import com.measure.core.designsystem.touchTarget
import com.measure.core.units.AreaFormatter
import com.measure.core.units.LengthFormatter
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
    val all by viewModel.projects.collectAsStateWithLifecycle()
    val projects = viewModel.visible(all)
    var editing by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }

    // Only worth showing once there are enough plans to have trouble finding one. Below
    // that the controls are two rows of furniture above a list you can already read.
    val searchable = (all?.size ?: 0) >= SEARCH_THRESHOLD

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

            if (searchable) {
                item {
                    FindBar(
                        query = viewModel.query,
                        onQuery = viewModel::search,
                        sort = viewModel.sort,
                        onSort = viewModel::selectSort,
                    )
                }
            }

            when {
                projects == null -> Unit // Loading: say nothing rather than say "empty".
                // Two different nothings, and conflating them would be a small lie: one
                // means "you have measured nothing", the other "nothing here says that".
                all!!.isEmpty() -> item { EmptyState() }
                projects.isEmpty() -> item { NoMatches(viewModel.query) }
                else -> items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        onOpen = { onOpenProject(project.id) },
                        onEdit = { editing = project },
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

    editing?.let { project ->
        DetailsDialog(
            initialName = project.name,
            initialReference = project.reference,
            onDismiss = { editing = null },
            onConfirm = { name, reference ->
                viewModel.rename(project.id, name)
                viewModel.setReference(project.id, reference)
                editing = null
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
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onDeviceCheck)
                .touchTarget()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Device check",
                color = MeasureColours.OnScrimMuted,
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * Search, and how the list is ordered — docs/PRODUCT_PLAN.md M13.
 *
 * Both on one row of the screen rather than behind a menu. There are three orders and one
 * field; hiding four things behind a button to save two rows would mean nobody ever
 * discovered them, and a search nobody finds is a search that does not exist.
 */
@Composable
private fun FindBar(
    query: String,
    onQuery: (String) -> Unit,
    sort: ProjectSort,
    onSort: (ProjectSort) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MeasureColours.Scrim)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = MeasureColours.OnScrim, fontSize = 15.sp),
                    cursorBrush = SolidColor(MeasureColours.Ready),
                    modifier = Modifier.fillMaxWidth(),
                )
                // A placeholder rather than a label above the field: the row is already
                // recognisably a search box, and the hint says what it searches, which is
                // the part nobody would guess.
                if (query.isEmpty()) {
                    Text(
                        text = "Search name or reference",
                        color = MeasureColours.OnScrimMuted.copy(alpha = 0.7f),
                        fontSize = 15.sp,
                    )
                }
            }
            if (query.isNotEmpty()) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onQuery("") }
                        .touchTarget()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "Clear", color = MeasureColours.Ready, fontSize = 13.sp)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProjectSort.entries.forEach { option ->
                SortChip(
                    label = option.label,
                    selected = option == sort,
                    onClick = { onSort(option) },
                )
            }
        }
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MeasureColours.Ready else MeasureColours.Scrim)
            .clickable(onClick = onClick)
            .touchTarget()
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF06231F) else MeasureColours.OnScrimMuted,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * The list is empty because of the search, not because there is nothing.
 *
 * Distinct from [EmptyState] on purpose. "Nothing measured yet" in front of someone who
 * has measured thirty rooms reads as the app having lost them, and that is the single
 * worst thing a list screen can imply.
 */
@Composable
private fun NoMatches(query: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "No plans match “$query”",
            color = MeasureColours.OnScrim,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Your plans are all still here — only this search is empty.",
            color = MeasureColours.OnScrimMuted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
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
    onEdit: () -> Unit,
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
                outlines = project.thumbnailOutlines,
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
            // Above the contents, because when someone has bothered to write "14 Ash Road"
            // that is what they are scanning the list for — not how many rooms it has.
            if (project.reference.isNotBlank()) {
                Text(
                    text = project.reference,
                    color = MeasureColours.Ready,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
            CardAction("Edit", onEdit)
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
            // The value itself when there is only one, because "1 measurement" tells the
            // user nothing they wanted to know — the number is the whole point of it.
            soleMeasurement?.let { add(LengthFormatter.format(it, unitSystem)) }
                ?: add("$measurementCount ${if (measurementCount == 1) "measurement" else "measurements"}")
        }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun CardAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MeasureColours.ScrimSoft)
            .clickable(onClick = onClick)
            .touchTarget()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = MeasureColours.OnScrimMuted, fontSize = 12.sp)
    }
}

@Composable
private fun NewMeasurementButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = "New measurement",
        modifier = modifier
            .clip(CircleShape)
            .background(MeasureColours.Ready)
            .clickable(onClick = onClick)
            .touchTarget()
            .padding(horizontal = 28.dp, vertical = 16.dp),
        color = Color(0xFF06231F),
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
    )
}

/**
 * The name and the reference, edited together.
 *
 * One dialog rather than two entries on the card. They are the same act — saying which
 * plan this is — and splitting them would put a second, rarely-used menu item beside a
 * frequently-used one, which is how a card of actions turns into a card of clutter.
 */
@Composable
private fun DetailsDialog(
    initialName: String,
    initialReference: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, reference: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var reference by remember { mutableStateOf(initialReference) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Plan details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogField(value = name, onValueChange = { name = it }, hint = "Name")
                DialogField(
                    value = reference,
                    onValueChange = { reference = it },
                    hint = "Client, address, anything you would search for",
                )
                Text(
                    text = "The reference is searched along with the name. It appears on " +
                        "exports, so a plan sent to someone says whose it is.",
                    color = MeasureColours.OnScrimMuted,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, reference) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A field that looks like one.
 *
 * The hint is drawn behind rather than as a label, and the box is a shade off the dialog
 * so the edge is visible: the editor shipped fields that were the same colour as the panel
 * behind them and read as gaps rather than as inputs, which is a fault worth not repeating
 * in a second screen.
 */
@Composable
private fun DialogField(value: String, onValueChange: (String) -> Unit, hint: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MeasureColours.ScrimSoft)
            .touchTarget()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = hint,
                color = MeasureColours.OnScrimMuted.copy(alpha = 0.7f),
                fontSize = 15.sp,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = MeasureColours.OnScrim, fontSize = 15.sp),
            cursorBrush = SolidColor(MeasureColours.Ready),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Below this many plans, finding one is not a problem worth putting controls on screen for. */
private const val SEARCH_THRESHOLD = 5
