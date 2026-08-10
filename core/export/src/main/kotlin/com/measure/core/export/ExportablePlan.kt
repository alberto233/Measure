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
    /**
     * The user's own label — a client, an address — or empty.
     *
     * On the drawing because that is where it earns its keep: "Plan 3" identifies a file
     * on a phone, and "14 Ash Road" identifies a drawing in somebody else's inbox.
     *
     * Declared after [rooms] rather than beside [name] so that constructing a plan
     * positionally still means what it used to.
     */
    val reference: String = "",
    /** Distances taken in the room with the camera. */
    val measurements: List<ExportableMeasurement> = emptyList(),
    /** Distances drawn on the plan afterwards. */
    val distances: List<ExportableDistance> = emptyList(),
    val unitSuffix: String = "m",
    /**
     * The wording of [arrangementCaveat], supplied rather than written here.
     *
     * This module is pure Kotlin and cannot read a translation, and this sentence is prose
     * the user's client reads on a drawing. The English default is what the exporters' own
     * tests assert against; the app overrides it from `res/values/strings.xml`, and there is
     * exactly one place that builds a plan, so there is exactly one place to get it wrong.
     */
    val arrangementNote: String =
        "Rooms placed by hand — each room is measured, the space between them is not",
    /**
     * Whether how the rooms sit relative to each other was measured.
     *
     * False once a plan holds rooms from more than one AR session: each session gives the
     * phone a new origin, so the app sets later rooms down beside the earlier ones and the
     * arrangement is a layout somebody made rather than a survey.
     *
     * It has to travel into the file. An export is where every hint the screen gave is
     * lost — the note above the plan, the room the user dragged into place — and the file
     * outlives the conversation that produced it. Someone scaling a corridor off a printed
     * drawing has no way to know the drawing was never entitled to show one.
     */
    val arrangementMeasured: Boolean = true,
) {
    val isEmpty: Boolean get() = rooms.isEmpty() && measurements.isEmpty() && distances.isEmpty()

    val allPoints: List<Vec2>
        get() = rooms.flatMap { it.outline } + distances.flatMap { listOf(it.from, it.to) }

    /**
     * The dimension strings for the drawing, **one set per room**.
     *
     * Every run is drawn on an export, unlike in the app, where one is shown at a time and
     * the rest are bare lines. The difference is deliberate: on screen a number can be
     * asked for, and a plan carrying all of them at once is unreadable on a phone. On
     * paper there is nobody to ask, so a drawing that does not carry its dimensions is a
     * picture of a room rather than a description of one.
     *
     * Per room for the same two reasons as on screen — a plan-wide string buries the run
     * anyone is looking for, and a run spanning two rooms is a distance the app was never
     * entitled to state. The export matters more, not less: nobody can tap a printed
     * drawing to ask where a number came from.
     */
    val dimensions: List<DimensionChain>
        get() = rooms.filter { it.outline.size >= 3 }
            .flatMap { DimensionChains.chains(listOf(it.outline)) }

    val totalFloorArea: Double get() = rooms.sumOf { it.floorArea }

    /** The name, with the reference after it when there is one. For a title block. */
    val title: String get() = if (reference.isBlank()) name else "$name · $reference"

    /**
     * The one sentence every format says when [arrangementMeasured] is false, or null.
     *
     * Read once here rather than per exporter so the six files cannot end up making six
     * differently worded promises — and so the wording can be argued about in one place.
     */
    val arrangementCaveat: String?
        get() = if (arrangementMeasured) null else arrangementNote
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
) {
    /** First, because it is the one most people mean by "send me the plan". */
    PDF("pdf", "application/pdf"),
    PNG("png", "image/png"),
    SVG("svg", "image/svg+xml"),

    // The registered type is image/vnd.dxf. application/dxf is a common invention and
    // resolves to nothing on a phone, which is how a share ends up with no apps offered.
    DXF("dxf", "image/vnd.dxf"),
    CSV("csv", "text/csv"),
    JSON("json", "application/json"),
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
