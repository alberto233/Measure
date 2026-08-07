package com.measure.feature.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.measure.core.data.ProjectDetail
import com.measure.core.data.SavedRoom
import com.measure.core.export.CsvExporter
import com.measure.core.export.DxfExporter
import com.measure.core.export.ExportFormat
import com.measure.core.export.ExportableDistance
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

    /**
     * A4 at 72 points per inch, which is the unit `PdfDocument` works in.
     *
     * A real page size rather than an arbitrary rectangle, because the point of a PDF is
     * that it prints — and a page that is not a paper size comes out of a printer scaled
     * by an unknown amount, which for a floor plan is worse than useless.
     */
    private const val A4_SHORT_POINTS = 595
    private const val A4_LONG_POINTS = 842

    /** Wide enough to stay sharp on a laptop, small enough to send over a message. */
    private const val PNG_LONG_EDGE = 2000

    fun render(project: ProjectDetail, format: ExportFormat): String {
        val plan = project.toExportable()
        return when (format) {
            ExportFormat.SVG -> SvgExporter.export(plan)
            ExportFormat.DXF -> DxfExporter.export(plan)
            ExportFormat.CSV -> CsvExporter.export(plan)
            ExportFormat.JSON -> JsonExporter.export(plan)
            // Not text. Handled by write(), and unreachable through this path.
            ExportFormat.PDF, ExportFormat.PNG -> error("${format.label} is not a text format")
        }
    }

    private fun write(project: ProjectDetail, format: ExportFormat, file: File) {
        if (format.isText) {
            file.writeText(render(project, format))
            return
        }

        val plan = project.toExportable()
        // Orientation follows the plan rather than a default, so a long thin flat is not
        // squeezed into a portrait page with two thirds of it blank.
        val points = plan.allPoints
        val wide = points.isNotEmpty() &&
            (points.maxOf { it.x } - points.minOf { it.x }) >
            (points.maxOf { it.y } - points.minOf { it.y })

        when (format) {
            ExportFormat.PDF -> {
                val width = if (wide) A4_LONG_POINTS else A4_SHORT_POINTS
                val height = if (wide) A4_SHORT_POINTS else A4_LONG_POINTS
                val document = PdfDocument()
                try {
                    val page = document.startPage(
                        PdfDocument.PageInfo.Builder(width, height, 1).create(),
                    )
                    PlanDrawing.draw(page.canvas, plan, width.toFloat(), height.toFloat(), "m²")
                    document.finishPage(page)
                    file.outputStream().use(document::writeTo)
                } finally {
                    // Closed whatever happened: a PdfDocument left open holds native
                    // memory for the life of the process.
                    document.close()
                }
            }

            ExportFormat.PNG -> {
                val width = if (wide) PNG_LONG_EDGE else PNG_LONG_EDGE * A4_SHORT_POINTS / A4_LONG_POINTS
                val height = if (wide) PNG_LONG_EDGE * A4_SHORT_POINTS / A4_LONG_POINTS else PNG_LONG_EDGE
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    PlanDrawing.draw(Canvas(bitmap), plan, width.toFloat(), height.toFloat(), "m²")
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally {
                    bitmap.recycle()
                }
            }

            else -> error("${format.label} is a text format")
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
        write(project, format, file)

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.exports",
            file,
        )

        fun intentOf(type: String) = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, project.name)
            // Read permission travels with the intent rather than being granted to the
            // world: a FileProvider path is not readable by another app without it.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val exact = intentOf(format.mimeType)
        // Falls back to a generic type when nothing on the phone claims the exact one.
        // SVG and DXF are the cases: both have real registered types that most handsets
        // have never heard of, and an accurate type nothing resolves produces a share
        // sheet with nothing in it — which reads as the export having failed.
        return if (exact.resolveActivity(context.packageManager) != null) {
            exact
        } else {
            intentOf(GENERIC_MIME_TYPE)
        }
    }

    /** What a file manager or a mail client will accept when nothing else will. */
    private const val GENERIC_MIME_TYPE = "application/octet-stream"
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
    reference = reference,
    rooms = rooms.map(SavedRoom::toExportable),
    measurements = measurements.map {
        ExportableMeasurement(
            label = it.label ?: it.mode.label,
            length = it.length.metres,
            sigma = it.sigma.metres,
            mode = it.mode.label,
        )
    },
    // Distances drawn on the plan were being left out of every export entirely, which
    // made the files quietly less than what the user had on screen.
    distances = planMeasurements.mapNotNull { saved ->
        val measurement = saved.measurement ?: return@mapNotNull null
        ExportableDistance(
            from = measurement.from.position,
            to = measurement.to.position,
            length = measurement.length,
            description = saved.label
                ?: "${measurement.from.description} to ${measurement.to.description}",
        )
    },
    // The file has to carry this or it makes a claim the screen was careful not to.
    arrangementMeasured = !hasUnrelatedCaptures,
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
