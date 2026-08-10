package com.traza.core.export

import java.util.Locale

/**
 * A plan as a spreadsheet.
 *
 * The row-per-room table someone pastes into a quote. Areas and perimeters are what get
 * multiplied by a price per square metre, and doing that by hand off a drawing is exactly
 * the tedium worth removing.
 */
object CsvExporter {

    /**
     * The words in the table, supplied by the caller.
     *
     * Header rows arrive whole rather than column by column, because a header row is a
     * sentence a translator needs to see together — the unit in brackets after each name is
     * part of the name, and splitting them into seven strings invites six of them to agree
     * and one not to.
     *
     * The English defaults are what this module's own tests assert against. The app passes
     * translated ones; see `feature/export`.
     */
    data class Labels(
        val rooms: String =
            "Room,Floor area (m2),Perimeter (m),Ceiling height (m),Wall area (m2),Volume (m3),Misclosure (%)",
        val total: String = "Total",
        val note: String = "Note",
        val measurements: String = "Measurement,Length (m),Tolerance (m),Mode",
        val distances: String = "Distance off the plan,Length (m)",
    )

    fun export(plan: ExportablePlan, labels: Labels = Labels()): String = buildString {
        append("${labels.rooms}\n")

        plan.rooms.forEach { room ->
            val height = room.ceilingHeight
            val wallArea = height?.let { room.perimeter * it - room.openings.sumOf { o -> o.width * o.height } }
            val volume = height?.let { room.floorArea * it }

            append(field(room.name))
            append(",${number(room.floorArea)}")
            append(",${number(room.perimeter)}")
            append(",${height?.let(::number).orEmpty()}")
            append(",${wallArea?.let(::number).orEmpty()}")
            append(",${volume?.let(::number).orEmpty()}")
            // Blank rather than zero when the loop was never re-observed. A zero here
            // would read as a perfect capture rather than as an unperformed check.
            append(",${room.misclosure?.let { number(it * 100) }.orEmpty()}")
            append("\n")
        }

        if (plan.rooms.size > 1) {
            append("${field(labels.total)},${number(plan.totalFloorArea)},,,,,\n")
        }

        // A spreadsheet has no drawing to qualify, but it does carry room-by-room numbers
        // someone may add up as if the rooms were surveyed together.
        plan.arrangementCaveat?.let { append("\n${field(labels.note)},${field(it)}\n") }

        if (plan.measurements.isNotEmpty()) {
            append("\n${labels.measurements}\n")
            plan.measurements.forEach {
                append("${field(it.label)},${number(it.length)},${number(it.sigma)},${field(it.mode)}\n")
            }
        }

        // Kept in their own table and labelled as taken off the plan, because a
        // spreadsheet strips every visual cue that told the user which was which.
        if (plan.distances.isNotEmpty()) {
            append("\n${labels.distances}\n")
            plan.distances.forEach {
                append("${field(it.description)},${number(it.length)}\n")
            }
        }
    }

    /**
     * Quotes a field, always.
     *
     * Unconditional because the alternative is deciding per value, and the value that
     * decides wrong is a room called "Kitchen, small" — which turns one row into two
     * columns and shifts every number after it into the wrong heading.
     */
    private fun field(value: String) = "\"${value.replace("\"", "\"\"")}\""

    private fun number(value: Double) = String.format(Locale.ROOT, "%.3f", value)
}

/**
 * The whole project, in the form this app can read back.
 *
 * This is the backup, and the only export that loses nothing. Hand-written rather than
 * produced by a serialisation library on purpose: the file is a promise to a user that
 * their measurements survive, so its shape should change only when someone decides to
 * change it — never as a side effect of a data class gaining a field or a library
 * changing how it names things.
 *
 * [VERSION] is the first thing in the file so that a reader can refuse a future one
 * politely instead of silently misreading it.
 */
object JsonExporter {

    const val VERSION = 1

    fun export(plan: ExportablePlan): String = buildString {
        append("{\n")
        append("  \"version\": $VERSION,\n")
        append("  \"application\": \"Traza\",\n")
        append("  \"units\": \"metres\",\n")
        append("  \"name\": ${string(plan.name)},\n")
        append("  \"reference\": ${string(plan.reference)},\n")
        // Machine-readable, because this is the format something else reads back, and the
        // thing it most needs to know about the coordinates is whether they mean anything
        // between one room and the next.
        append("  \"arrangementMeasured\": ${plan.arrangementMeasured},\n")
        append("  \"rooms\": [\n")
        plan.rooms.forEachIndexed { index, room ->
            append("    {\n")
            append("      \"name\": ${string(room.name)},\n")
            append("      \"floorArea\": ${number(room.floorArea)},\n")
            append("      \"perimeter\": ${number(room.perimeter)},\n")
            append("      \"ceilingHeight\": ${room.ceilingHeight?.let(::number) ?: "null"},\n")
            append("      \"misclosure\": ${room.misclosure?.let(::number) ?: "null"},\n")
            append("      \"corners\": [")
            append(room.outline.joinToString(", ") { "[${number(it.x)}, ${number(it.y)}]" })
            append("],\n")
            append("      \"wallLengths\": [")
            append(room.wallLengths.joinToString(", ", transform = ::number))
            append("],\n")
            append("      \"openings\": [\n")
            room.openings.forEachIndexed { openingIndex, opening ->
                append("        {")
                append("\"kind\": ${string(opening.kind)}, ")
                append("\"wall\": ${opening.wallIndex}, ")
                append("\"offset\": ${number(opening.offset)}, ")
                append("\"width\": ${number(opening.width)}, ")
                append("\"height\": ${number(opening.height)}, ")
                append("\"sill\": ${number(opening.sillHeight)}}")
                append(if (openingIndex == room.openings.lastIndex) "\n" else ",\n")
            }
            append("      ]\n")
            append(if (index == plan.rooms.lastIndex) "    }\n" else "    },\n")
        }
        append("  ],\n")
        append("  \"measurements\": [\n")
        plan.measurements.forEachIndexed { index, measurement ->
            append("    {")
            append("\"label\": ${string(measurement.label)}, ")
            append("\"length\": ${number(measurement.length)}, ")
            append("\"tolerance\": ${number(measurement.sigma)}, ")
            append("\"mode\": ${string(measurement.modeKey)}}")
            append(if (index == plan.measurements.lastIndex) "\n" else ",\n")
        }
        append("  ],\n")
        // Separate from "measurements" on purpose. One kind was observed in the room and
        // the other derived from the drawing, and a reader that could not tell them apart
        // would be free to treat them as equally good.
        append("  \"distancesOffThePlan\": [\n")
        plan.distances.forEachIndexed { index, distance ->
            append("    {")
            append("\"description\": ${string(distance.description)}, ")
            append("\"length\": ${number(distance.length)}, ")
            append("\"from\": [${number(distance.from.x)}, ${number(distance.from.y)}], ")
            append("\"to\": [${number(distance.to.x)}, ${number(distance.to.y)}]}")
            append(if (index == plan.distances.lastIndex) "\n" else ",\n")
        }
        append("  ]\n")
        append("}\n")
    }

    private fun string(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                // Control characters are illegal raw in JSON and turn up in names copied
                // in from elsewhere more often than anyone expects.
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    private fun number(value: Double) = String.format(Locale.ROOT, "%.4f", value)
}
