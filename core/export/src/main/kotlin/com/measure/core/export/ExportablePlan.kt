package com.measure.core.export

import com.measure.core.geometry.Vec2
import com.measure.core.geometry.plan.DimensionChain
import com.measure.core.geometry.plan.DimensionChains

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
    /** Distances drawn on the plan afterwards. */
    val distances: List<ExportableDistance> = emptyList(),
    val unitSuffix: String = "m",
) {
    val isEmpty: Boolean get() = rooms.isEmpty() && measurements.isEmpty() && distances.isEmpty()

    val allPoints: List<Vec2>
        get() = rooms.flatMap { it.outline } + distances.flatMap { listOf(it.from, it.to) }

    /**
     * The dimension strings for the drawing.
     *
     * Every run is drawn on an export, unlike in the app, where one is shown at a time and
     * the rest are bare lines. The difference is deliberate: on screen a number can be
     * asked for, and a plan carrying all of them at once is unreadable on a phone. On
     * paper there is nobody to ask, so a drawing that does not carry its dimensions is a
     * picture of a room rather than a description of one.
     */
    val dimensions: List<DimensionChain>
        get() = DimensionChains.chains(rooms.filter { it.outline.size >= 3 }.map { it.outline })

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

/**
 * A distance drawn on the plan rather than measured in the room.
 *
 * Exported, and marked as derived wherever it appears. It is genuinely useful — it is
 * usually the "will it fit" answer somebody wanted the drawing for — but it is a
 * consequence of the plan and not an observation of the room, and a printed drawing is
 * exactly where that distinction stops being visible unless it is written down.
 */
data class ExportableDistance(
    val from: Vec2,
    val to: Vec2,
    val length: Double,
    val description: String,
)

/** The formats a plan can leave the app as. */
enum class ExportFormat(
    val extension: String,
    val mimeType: String,
    val label: String,
    val description: String,
) {
    /** First, because it is the one most people mean by "send me the plan". */
    PDF("pdf", "application/pdf", "PDF", "A page to print, email or attach to a quote"),
    // Named by its format like the rest. "Image" read as a different kind of thing
    // beside four formats that name themselves, which is a reason to hesitate over a
    // choice that should be obvious.
    PNG("png", "image/png", "PNG image", "A picture, for a message or a document"),
    SVG("svg", "image/svg+xml", "SVG drawing", "A scalable drawing that stays sharp at any size"),

    // The registered type is image/vnd.dxf. application/dxf is a common invention and
    // resolves to nothing on a phone, which is how a share ends up with no apps offered.
    DXF("dxf", "image/vnd.dxf", "DXF drawing", "Opens in CAD — AutoCAD, LibreCAD, QCAD"),
    CSV("csv", "text/csv", "CSV table", "Room sizes as a spreadsheet"),
    JSON("json", "application/json", "Project file", "Everything, in a form this app can read back"),
    ;

    /** Whether the file is text this module writes, or a picture Android has to render. */
    val isText: Boolean get() = this != PDF && this != PNG

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
