package com.measure.core.export

import com.measure.core.geometry.Vec2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class ExportersTest {

    private val room = ExportableRoom(
        name = "Kitchen",
        outline = listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 4.0), Vec2(0.0, 4.0)),
        floorArea = 20.0,
        perimeter = 18.0,
        ceilingHeight = 2.4,
        wallLengths = listOf(5.0, 4.0, 5.0, 4.0),
        openings = listOf(
            ExportableOpening("DOOR", wallIndex = 0, offset = 1.0, width = 0.83, height = 2.04, sillHeight = 0.0),
        ),
        misclosure = 0.004,
    )

    private val plan = ExportablePlan(
        name = "Plan 1",
        rooms = listOf(room),
        measurements = listOf(ExportableMeasurement("Ceiling height", 2.44, 0.03, "Plumb")),
    )

    // --- SVG ---------------------------------------------------------------------------

    @Test
    fun `the svg is well formed and declares its size in millimetres`() {
        val svg = SvgExporter.export(plan)

        assertTrue(svg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(svg.contains("xmlns=\"http://www.w3.org/2000/svg\""))
        assertTrue(svg.trimEnd().endsWith("</svg>"))
        assertTrue(svg.contains("mm\" height="), "the page should be sized in real units")
        assertEquals(count(svg, "<svg"), count(svg, "</svg>"))
        assertEquals(count(svg, "<g "), count(svg, "</g>"))
    }

    @Test
    fun `the svg is drawn at the scale it claims`() {
        // At 1:50 a five metre wall is 100 mm on the page.
        val svg = SvgExporter.export(plan, scaleDenominator = 50.0)
        assertTrue(svg.contains("1:50"), "the drawing must state its scale")

        val polygon = svg.substringAfter("<polygon points=\"").substringBefore("\"")
        val corners = polygon.split(" ").map { it.split(",").map(String::toDouble) }
        val width = corners.maxOf { it[0] } - corners.minOf { it[0] }
        val height = corners.maxOf { it[1] } - corners.minOf { it[1] }

        assertEquals(100.0, width, 0.01)
        assertEquals(80.0, height, 0.01)
    }

    @Test
    fun `the plan is flipped so that away from the viewer is up the page`() {
        val svg = SvgExporter.export(plan)
        val polygon = svg.substringAfter("<polygon points=\"").substringBefore("\"")
        val corners = polygon.split(" ").map { it.split(",").map(String::toDouble) }

        // Corner 0 is at plan y = 0, the nearest edge, so it must be the *lowest* on the
        // page — SVG's y grows downwards. Getting this backwards mirrors the plan, which
        // is the kind of error nobody notices until a room is built the wrong way round.
        assertEquals(corners.maxOf { it[1] }, corners[0][1], 1e-9)
    }

    @Test
    fun `a name with markup in it cannot break the document`() {
        val nasty = plan.copy(name = "Ben & Jo's <flat>")
        val svg = SvgExporter.export(nasty)

        assertTrue(svg.contains("Ben &amp; Jo's &lt;flat&gt;"))
        assertFalse(svg.contains("<flat>"))
    }

    @Test
    fun `an empty plan still produces a valid document`() {
        val svg = SvgExporter.export(ExportablePlan("Empty", emptyList()))
        assertTrue(svg.contains("<svg"))
        assertTrue(svg.trimEnd().endsWith("</svg>"))
    }

    // --- DXF ---------------------------------------------------------------------------

    @Test
    fun `the dxf has the sections a reader expects, in order`() {
        val dxf = DxfExporter.export(plan)

        val order = listOf("HEADER", "TABLES", "ENTITIES").map { dxf.indexOf(it) }
        assertTrue(order.all { it >= 0 }, "every section must be present")
        assertEquals(order.sorted(), order, "sections must appear in DXF's required order")
        assertTrue(dxf.trimEnd().endsWith("EOF"))
        assertEquals(3, count(dxf, "ENDSEC"))
    }

    @Test
    fun `the dxf is full size in metres`() {
        val dxf = DxfExporter.export(plan)

        // 70/6 is $INSUNITS = metres. Without it most readers assume millimetres and the
        // room comes out five millimetres across.
        assertTrue(dxf.contains("\$INSUNITS\n 70\n     6"))
        // The far corner is at 5, not 5000 and not 100.
        assertTrue(dxf.contains(" 10\n5.0000\n"), "a 5 m wall should be 5 units")
    }

    @Test
    fun `the room polygon is closed`() {
        val dxf = DxfExporter.export(plan)
        // 70/1 on the POLYLINE is the closed flag; without it the fourth wall is missing.
        assertTrue(dxf.contains("POLYLINE\n  8\nWALLS\n 66\n     1\n 70\n     1"))
        assertEquals(4, count(dxf, "VERTEX"))
        assertTrue(dxf.contains("SEQEND"))
    }

    @Test
    fun `a newline in a room name cannot corrupt the entities after it`() {
        val awkward = plan.copy(rooms = listOf(room.copy(name = "Kitchen\ndiner")))
        val dxf = DxfExporter.export(awkward)

        assertTrue(dxf.contains("  1\nKitchen diner\n"))
        assertTrue(dxf.trimEnd().endsWith("EOF"))
    }

    // --- CSV ---------------------------------------------------------------------------

    @Test
    fun `the csv has one row per room under a header`() {
        val csv = CsvExporter.export(plan)
        val lines = csv.trim().lines()

        assertTrue(lines[0].startsWith("Room,Floor area"))
        assertTrue(lines[1].startsWith("\"Kitchen\""))
        assertTrue(lines[1].contains("20.000"))
    }

    @Test
    fun `a comma in a room name does not become a new column`() {
        val csv = CsvExporter.export(plan.copy(rooms = listOf(room.copy(name = "Kitchen, small"))))
        val fields = csvFields(csv.lines()[1])

        assertEquals(7, fields.size, "the comma must stay inside its field")
        assertEquals("Kitchen, small", fields[0])
        assertEquals("20.000", fields[1])
    }

    /** A minimal reader, so the test checks the format rather than the string. */
    private fun csvFields(row: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < row.length) {
            val character = row[index]
            when {
                quoted && character == '"' && row.getOrNull(index + 1) == '"' -> {
                    current.append('"')
                    index++
                }

                character == '"' -> quoted = !quoted
                character == ',' && !quoted -> {
                    fields += current.toString()
                    current.clear()
                }

                else -> current.append(character)
            }
            index++
        }
        fields += current.toString()
        return fields
    }

    @Test
    fun `a quote in a room name is doubled, as the format requires`() {
        val csv = CsvExporter.export(plan.copy(rooms = listOf(room.copy(name = "The \"snug\""))))
        assertTrue(csv.contains("\"The \"\"snug\"\"\""))
    }

    @Test
    fun `an unmeasured misclosure is blank rather than zero`() {
        // Zero would read as a perfect capture rather than as a check never carried out.
        val csv = CsvExporter.export(plan.copy(rooms = listOf(room.copy(misclosure = null))))
        assertTrue(csv.lines()[1].endsWith(","), "the misclosure column should be empty")
    }

    // --- JSON --------------------------------------------------------------------------

    @Test
    fun `the json carries a version first, so a future file can be refused`() {
        val json = JsonExporter.export(plan)
        assertTrue(json.contains("\"version\": ${JsonExporter.VERSION}"))
        assertTrue(json.indexOf("\"version\"") < json.indexOf("\"rooms\""))
    }

    @Test
    fun `the json is balanced and holds the geometry`() {
        val json = JsonExporter.export(plan)

        assertEquals(count(json, "{"), count(json, "}"))
        assertEquals(count(json, "["), count(json, "]"))
        assertTrue(json.contains("[5.0000, 4.0000]"))
        assertTrue(json.contains("\"kind\": \"DOOR\""))
    }

    @Test
    fun `a quote or a control character in a name is escaped`() {
        val json = JsonExporter.export(plan.copy(name = "The \"snug\""))
        assertTrue(json.contains("\\\"snug\\\""))
        assertTrue(json.contains("\\u0007"))
    }

    // --- everything ---------------------------------------------------------------------

    @Test
    fun `numbers do not follow the phone's locale`() {
        // A phone set to Spanish writes a decimal comma by default, and an SVG coordinate
        // or a JSON number containing a comma is not the number it was meant to be. This
        // is the export bug that would only ever appear on someone else's device.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es-ES"))

            // Asserted by parsing rather than by matching a string: toDouble throws on
            // "124,00", which is exactly the corruption being guarded against, and it
            // catches every coordinate rather than one that happened to be picked.
            val polygon = SvgExporter.export(plan).substringAfter("<polygon points=\"").substringBefore("\"")
            polygon.split(" ").forEach { pair ->
                val parts = pair.split(",")
                assertEquals(2, parts.size, "\"$pair\" is not one coordinate pair")
                parts.forEach { it.toDouble() }
            }

            assertTrue(DxfExporter.export(plan).contains("5.0000"))
            assertTrue(JsonExporter.export(plan).contains("20.0000"))
            assertTrue(CsvExporter.export(plan).contains("20.000"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `a file name never contains a path separator`() {
        assertEquals("Flat-3_4.svg", ExportFormat.SVG.fileName("Flat 3/4"))
        assertEquals("plan.dxf", ExportFormat.DXF.fileName("   "))
        assertEquals("Plan-1.json", ExportFormat.JSON.fileName("Plan 1"))
    }

    private fun count(text: String, needle: String) =
        text.windowed(needle.length).count { it == needle }
}
