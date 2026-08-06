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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.measure.core.data.ProjectSort
import com.measure.core.data.ProjectSummary
import com.measure.core.designsystem.MeasureButton
import com.measure.core.designsystem.MeasureColours
import com.measure.core.designsystem.MeasureField
import com.measure.core.designsystem.MeasureRule
import com.measure.core.designsystem.MeasureShape
import com.measure.core.designsystem.MeasureSpace
import com.measure.core.designsystem.MeasureTag
import com.measure.core.designsystem.MeasureType
import com.measure.core.designsystem.PlanStyle
import com.measure.core.designsystem.PlanView
import com.measure.core.designsystem.touchTarget
import com.measure.core.units.AreaFormatter
import com.measure.core.units.LengthFormatter
import java.text.DateFormat
import java.util.Date

/**
 * The home screen: everything measured so far — docs/PRODUCT_PLAN.md M10a, direction A.
 *
 * The list is a stack of ruled entries rather than a stack of cards. Cards on a dark ground
 * become a field of grey rectangles competing with each other for the same attention; a
 * rule costs one pixel and leaves the names and the numbers as the only things with weight,
 * which is what a plan list is for.
 *
 * There is no capability gate in front of this. The capture screen reports every ARCore
 * failure itself, so gating the *list* of saved work behind a camera check would only stop
 * people reading plans they already have. The device report is one tap away.
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
            contentPadding = PaddingValues(
                start = MeasureSpace.Wide,
                end = MeasureSpace.Wide,
                top = MeasureSpace.Base,
                // Clears the bar below, which floats over the list rather than sitting in
                // it. Too small and the last entry can never be scrolled out from under
                // the bar — which is what happened: with six plans the device check button
                // came to rest underneath "New measurement" and the two overlapped.
                bottom = BOTTOM_BAR_CLEARANCE,
            ),
            verticalArrangement = Arrangement.spacedBy(MeasureSpace.Snug),
        ) {
            item { Header(all?.size ?: 0, onDeviceCheck) }

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
                    PlanEntry(
                        project = project,
                        onOpen = { onOpenProject(project.id) },
                        onEdit = { editing = project },
                        onDelete = { deleting = project },
                    )
                }
            }

            item {
                MeasureButton(
                    label = "Device check",
                    onClick = onDeviceCheck,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MeasureSpace.Base),
                )
            }
        }

        // Opaque, and ruled off from the list above it.
        //
        // It used to be a bare button floating over the list, which meant entries scrolled
        // past it in the gaps around its edges and, at six plans, the device check button
        // came to rest directly underneath it. A bar that covers what passes beneath it is
        // the difference between a fixed action and a rendering fault.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MeasureColours.Surface),
        ) {
            MeasureRule()
            MeasureButton(
                label = "New measurement",
                onClick = onNewMeasurement,
                primary = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MeasureSpace.Wide),
            )
        }
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
            containerColor = MeasureColours.Panel,
            title = { Text("Delete ${project.name}?", color = MeasureColours.OnScrim, style = MeasureType.Title) },
            text = {
                Text(
                    "Its rooms and measurements go with it. This cannot be undone.",
                    color = MeasureColours.OnScrimMuted,
                    style = MeasureType.Body,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(project.id)
                    deleting = null
                }) { Text("Delete", color = MeasureColours.Accent) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text("Keep", color = MeasureColours.OnScrimMuted)
                }
            },
        )
    }
}

/**
 * The masthead: what this is on the left, how much of it there is on the right.
 *
 * The count is set as a reading rather than as prose. It is the first instance of the rule
 * that carries this whole direction — a number is never a sentence.
 */
@Composable
private fun Header(count: Int, onDeviceCheck: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MeasureSpace.Snug)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                MeasureTag("measure")
                Text("Plans", color = MeasureColours.OnScrim, style = MeasureType.Display)
            }
            Column(horizontalAlignment = Alignment.End) {
                MeasureTag("saved")
                Text(
                    text = count.toString().padStart(2, '0'),
                    color = MeasureColours.Accent,
                    style = MeasureType.Display.copy(fontFamily = MeasureType.Value.fontFamily),
                )
            }
        }
        MeasureRule()
    }
}

/** Search, and how the list is ordered — docs/PRODUCT_PLAN.md M13. */
@Composable
private fun FindBar(
    query: String,
    onQuery: (String) -> Unit,
    sort: ProjectSort,
    onSort: (ProjectSort) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MeasureSpace.Tight)) {
        MeasureField(
            value = query,
            onValueChange = onQuery,
            hint = "Search name or reference",
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MeasureSpace.Tight)) {
            ProjectSort.entries.forEach { option ->
                MeasureButton(
                    label = option.label,
                    onClick = { onSort(option) },
                    selected = option == sort,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * One saved plan.
 *
 * The thumbnail is *drawn* from the stored corners rather than being a saved image. It
 * cannot go stale, there are no files to clean up when a project is deleted, and it is the
 * same drawing code as the live minimap — so a room looks the same here as it did while it
 * was being captured.
 */
@Composable
private fun PlanEntry(
    project: ProjectSummary,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(vertical = MeasureSpace.Snug),
            horizontalArrangement = Arrangement.spacedBy(MeasureSpace.Snug),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(MeasureShape.Edge))
                    .background(MeasureColours.Panel),
            ) {
                PlanView(
                    outlines = project.thumbnailOutlines,
                    modifier = Modifier.fillMaxSize(),
                    style = PlanStyle(strokeWidth = 1.6f, filled = false, showVertices = false, paddingDp = 8f),
                )
            }

            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MeasureSpace.Hair),
            ) {
                Text(project.name, color = MeasureColours.OnScrim, style = MeasureType.Label)
                // The reference under the name, because when somebody has bothered to
                // write "14 Ash Road" that is what they are scanning the list for.
                MeasureTag(
                    text = project.reference.ifBlank { "no reference" },
                    colour = MeasureColours.OnScrimMuted,
                )
            }

            project.headline()?.let { (value, unit) ->
                Column(horizontalAlignment = Alignment.End) {
                    Text(value, color = MeasureColours.OnScrim, style = MeasureType.Value)
                    MeasureTag(unit)
                }
            }

            Box(
                Modifier
                    .clip(RoundedCornerShape(MeasureShape.Edge))
                    .clickable { menu = true }
                    .touchTarget(),
                contentAlignment = Alignment.Center,
            ) {
                Text("⋯", color = MeasureColours.OnScrimMuted, style = MeasureType.Title)
            }
        }
        MeasureRule()
    }

    if (menu) {
        AlertDialog(
            onDismissRequest = { menu = false },
            containerColor = MeasureColours.Panel,
            title = { Text(project.name, color = MeasureColours.OnScrim, style = MeasureType.Title) },
            text = {
                Text(
                    text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(project.updatedAt)) + " · " + project.describeContents(),
                    color = MeasureColours.OnScrimMuted,
                    style = MeasureType.Small,
                )
            },
            confirmButton = {
                TextButton(onClick = { menu = false; onEdit() }) {
                    Text("Edit details", color = MeasureColours.Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { menu = false; onDelete() }) {
                    Text("Delete", color = MeasureColours.Blocked)
                }
            },
        )
    }
}

/**
 * The one number worth setting large on a list row.
 *
 * Total area for a plan with rooms; the value itself for a plan holding a single
 * measurement, because "1 measurement" tells the user nothing they wanted to know.
 */
private fun ProjectSummary.headline(): Pair<String, String>? = when {
    roomCount > 0 -> AreaFormatter.format(totalArea, unitSystem).split(" ").let {
        it.first() to it.drop(1).joinToString(" ")
    }
    soleMeasurement != null -> LengthFormatter.format(soleMeasurement!!, unitSystem).split(" ").let {
        it.first() to it.drop(1).joinToString(" ")
    }
    measurementCount > 0 -> measurementCount.toString() to "measurements"
    else -> null
}

private fun ProjectSummary.describeContents(): String {
    if (isEmpty) return "Empty"
    return buildList {
        if (roomCount > 0) add("$roomCount ${if (roomCount == 1) "room" else "rooms"}")
        if (measurementCount > 0) {
            add("$measurementCount ${if (measurementCount == 1) "measurement" else "measurements"}")
        }
    }.joinToString(" · ")
}

@Composable
private fun NoMatches(query: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MeasureSpace.Tight),
    ) {
        Text("No plans match “$query”", color = MeasureColours.OnScrim, style = MeasureType.Title)
        Text(
            text = "Your plans are all still here — only this search is empty.",
            color = MeasureColours.OnScrimMuted,
            style = MeasureType.Small,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MeasureSpace.Snug),
    ) {
        MeasureTag("no plans yet")
        Text("Nothing measured", color = MeasureColours.OnScrim, style = MeasureType.Title)
        Text(
            text = "Point the camera at a room and walk the corners.\nEverything saves as you go.",
            color = MeasureColours.OnScrimMuted,
            style = MeasureType.Body,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The name and the reference, edited together.
 *
 * One dialog rather than two entries on the card. They are the same act — saying which plan
 * this is — and splitting them would put a second, rarely-used control beside a frequently
 * used one, which is how a row of actions turns into clutter.
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
        containerColor = MeasureColours.Panel,
        title = { Text("Plan details", color = MeasureColours.OnScrim, style = MeasureType.Title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MeasureSpace.Snug)) {
                MeasureField(name, { name = it }, hint = "Name", modifier = Modifier.fillMaxWidth())
                MeasureField(
                    value = reference,
                    onValueChange = { reference = it },
                    hint = "Client, address, anything you would search for",
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "The reference is searched along with the name, and appears on " +
                        "exports — so a plan sent to someone says whose it is.",
                    color = MeasureColours.OnScrimMuted,
                    style = MeasureType.Small,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, reference) }) {
                Text("Save", color = MeasureColours.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MeasureColours.OnScrimMuted) }
        },
    )
}

/** Below this many plans, finding one is not a problem worth putting controls on screen for. */
private const val SEARCH_THRESHOLD = 5

/** A rule, a button and its padding — see the bar at the bottom of the screen. */
private val BOTTOM_BAR_CLEARANCE = 112.dp
