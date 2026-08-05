package com.measure.core.export

import com.measure.core.geometry.Vec2

/**
 * A plan reduced to what an export needs, and nothing else.
 *
 * Deliberately its own type rather than the database's. An exporter that took `SavedRoom`
 * would put the schema into the file format, and the two change for entirely unrelated
 * reasons — a migration should never be able to alter what a DXF looks like. It also
 * keeps this module free of Android, so every format can be tested on the JVM, which
 * matters more here than almost anywhere: a single malformed character produces a file
 * that opens as an error dialogue in someone else's software rather than as a plan, and
 * that is not something a device test would catch either.
 */
data class ExportablePlan(
    val name: String,
    val rooms: List<ExportableRoom>,
    /** Distances taken in the room with the camera. */
    val measurements: List<ExportableMeasurement> = emptyList(),
    val unitSuffix: String = "m",
) {
    val isEmpty: Boolean get() = rooms.isEmpty() && measurements.isEmpty()

    val allPoints: List<Vec2> get() = rooms.flatMap { it.outline }

    val totalFloorArea: Double get() = rooms.sumOf { it.floorArea }
}

data class ExportableRoom(
    val name: String,
    /** Corners in order. The polygon is closed by returning to the first. */
    val outline: List<Vec2>,
    val floorArea: Double,
    val perimeter: Double,
    val ceilingHeight: Double? = null,
    /** Wall index to its length, for every wall. */
    val wallLengths: List<Double> = emptyList(),
    val openings: List<ExportableOpening> = emptyList(),
    /**
     * How well the capture closed, as a fraction of the perimeter, or null when the loop
     * was never re-observed and there is therefore nothing to report.
     */
    val misclosure: Double? = null,
)

data class ExportableOpening(
    val kind: String,
    val wallIndex: Int,
    val offset: Double,
    val width: Double,
    val height: Double,
    val sillHeight: Double,
)

data class ExportableMeasurement(
    val label: String,
    val length: Double,
    val sigma: Double,
    val mode: String,
)

/** The formats a plan can leave the app as. */
enum class ExportFormat(
    val extension: String,
    val mimeType: String,
    val label: String,
    val description: String,
) {
    SVG("svg", "image/svg+xml", "SVG drawing", "A scalable drawing, for a document or a printer"),
    DXF("dxf", "application/dxf", "DXF drawing", "Opens in CAD — AutoCAD, LibreCAD, QCAD"),
    CSV("csv", "text/csv", "CSV table", "Room sizes as a spreadsheet"),
    JSON("json", "application/json", "Project file", "Everything, in a form this app can read back"),
    ;

    fun fileName(planName: String): String {
        // Anything a filesystem or a share target might choke on becomes an underscore.
        // A plan called "Flat 3/4" would otherwise produce a path with a directory in it.
        val safe = planName.trim()
            .ifEmpty { "plan" }
            .map { if (it.isLetterOrDigit() || it == '-' || it == ' ') it else '_' }
            .joinToString("")
            .replace(' ', '-')
        return "$safe.$extension"
    }
}
