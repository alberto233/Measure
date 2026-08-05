package com.measure.feature.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.measure.core.data.ProjectDetail
import com.measure.core.data.SavedRoom
import com.measure.core.export.CsvExporter
import com.measure.core.export.DxfExporter
import com.measure.core.export.ExportFormat
import com.measure.core.export.ExportableMeasurement
import com.measure.core.export.ExportableOpening
import com.measure.core.export.ExportablePlan
import com.measure.core.export.ExportableRoom
import com.measure.core.export.JsonExporter
import com.measure.core.export.SvgExporter
import java.io.File

/**
 * Turns a saved project into a file and hands it to whatever the user wants to send it
 * with — M7.
 *
 * Everything above the file system lives in `:core:export` and is pure, so what is left
 * here is only the Android part: write bytes somewhere a share target can read them, and
 * fire the intent. That split is why the formats can be tested at all.
 */
object PlanExporter {

    /**
     * Files are written into a subdirectory of the cache.
     *
     * The cache rather than anywhere permanent because these are copies: the project is
     * the original and this is a rendering of it. Android is free to reclaim the space
     * once it has been shared, which is the correct lifetime for something whose only
     * purpose was to be handed to another app.
     */
    private const val DIRECTORY = "exports"

    fun render(project: ProjectDetail, format: ExportFormat): String {
        val plan = project.toExportable()
        return when (format) {
            ExportFormat.SVG -> SvgExporter.export(plan)
            ExportFormat.DXF -> DxfExporter.export(plan)
            ExportFormat.CSV -> CsvExporter.export(plan)
            ExportFormat.JSON -> JsonExporter.export(plan)
        }
    }

    /**
     * Writes the export and returns an intent that will share it.
     *
     * The directory is emptied first. Exports accumulate otherwise — a plan renamed or
     * re-exported leaves the old file behind — and a share sheet is not a place anyone
     * looks for stale copies of their own data.
     */
    fun share(context: Context, project: ProjectDetail, format: ExportFormat): Intent {
        val directory = File(context.cacheDir, DIRECTORY).apply {
            deleteRecursively()
            mkdirs()
        }
        val file = File(directory, format.fileName(project.name))
        file.writeText(render(project, format))

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.exports",
            file,
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, project.name)
            // Read permission travels with the intent rather than being granted to the
            // world: a FileProvider path is not readable by another app without it.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

/**
 * Maps the database's shape onto the exporters' own.
 *
 * The translation exists so that a schema change cannot alter a file format. They move
 * for entirely unrelated reasons, and a migration silently changing what a DXF looks like
 * would be found by whoever opened it in CAD a month later.
 */
internal fun ProjectDetail.toExportable() = ExportablePlan(
    name = name,
    rooms = rooms.map(SavedRoom::toExportable),
    measurements = measurements.map {
        ExportableMeasurement(
            label = it.label ?: it.mode.label,
            length = it.length.metres,
            sigma = it.sigma.metres,
            mode = it.mode.label,
        )
    },
)

internal fun SavedRoom.toExportable() = ExportableRoom(
    name = name,
    outline = outline,
    floorArea = area.squareMetres,
    perimeter = perimeter.metres,
    ceilingHeight = ceilingHeight,
    wallLengths = outline.indices.map { index ->
        outline[index].distanceTo(outline[(index + 1) % outline.size])
    },
    openings = openings.values.flatten().map {
        ExportableOpening(
            kind = it.opening.kind.name,
            wallIndex = it.wallIndex,
            offset = it.opening.offset,
            width = it.opening.width,
            height = it.opening.height,
            sillHeight = it.opening.sillHeight,
        )
    },
    // Null rather than the stored zero when the loop was never re-observed: a zero here
    // would travel into a spreadsheet as a perfect capture.
    misclosure = misclosure.takeIf { it > 0.0 },
)
