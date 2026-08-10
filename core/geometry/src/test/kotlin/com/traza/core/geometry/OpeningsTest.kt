package com.traza.core.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A 5 x 4 m room: 20 m² floor, 18 m perimeter. */
private val room = Polygon(
    listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 4.0), Vec2(0.0, 4.0)),
)

private val door = Opening(OpeningKind.DOOR, offset = 0.4, width = 0.83, height = 2.04)
private val window = Opening(
    OpeningKind.WINDOW,
    offset = 1.2,
    width = 1.4,
    height = 1.2,
    sillHeight = 0.9,
)

class OpeningTest {

    @Test
    fun `area and top height`() {
        assertEquals(0.83 * 2.04, door.area.squareMetres, 1e-12)
        assertEquals(2.04, door.topHeight, 1e-12)
        assertEquals(2.1, window.topHeight, 1e-12)
    }

    @Test
    fun `an opening has to fit the wall it is in`() {
        assertTrue(door.fitsIn(wallLength = 5.0, ceilingHeight = 2.4))
        assertFalse(door.copy(offset = 4.5).fitsIn(wallLength = 5.0, ceilingHeight = 2.4))
        assertFalse(door.fitsIn(wallLength = 1.0, ceilingHeight = 2.4))
    }

    @Test
    fun `an opening has to fit under the ceiling`() {
        assertFalse(window.fitsIn(wallLength = 5.0, ceilingHeight = 2.0))
        assertTrue(window.fitsIn(wallLength = 5.0, ceilingHeight = 2.4))
    }

    @Test
    fun `a centimetre of slack, because a frame measured by hand is not exact`() {
        assertTrue(door.copy(offset = 5.0 - 0.83 + 0.005).fitsIn(5.0, 2.4))
        assertFalse(door.copy(offset = 5.0 - 0.83 + 0.05).fitsIn(5.0, 2.4))
    }

    @Test
    fun `nonsense dimensions do not fit anything`() {
        assertFalse(door.copy(width = 0.0).fitsIn(5.0, 2.4))
        assertFalse(door.copy(height = -1.0).fitsIn(5.0, 2.4))
        assertFalse(door.copy(offset = -0.2).fitsIn(5.0, 2.4))
    }
}

class SurfaceCalculatorTest {

    @Test
    fun `a bare room`() {
        val surfaces = SurfaceCalculator.compute(room, ceilingHeight = 2.4)

        assertEquals(20.0, surfaces.floorArea.squareMetres, 1e-9)
        assertEquals(18.0, surfaces.perimeter.metres, 1e-9)
        assertEquals(43.2, surfaces.grossWallArea.squareMetres, 1e-9)
        assertEquals(0.0, surfaces.openingArea.squareMetres, 1e-9)
        assertEquals(43.2, surfaces.netWallArea.squareMetres, 1e-9)
        assertEquals(48.0, surfaces.volume.cubicMetres, 1e-9)
    }

    @Test
    fun `openings come out of the wall area but not the volume`() {
        val surfaces = SurfaceCalculator.compute(room, 2.4, listOf(door, window))

        val expected = door.area.squareMetres + window.area.squareMetres
        assertEquals(expected, surfaces.openingArea.squareMetres, 1e-9)
        assertEquals(43.2 - expected, surfaces.netWallArea.squareMetres, 1e-9)
        // A door does not change how much air the room holds.
        assertEquals(48.0, surfaces.volume.cubicMetres, 1e-9)
    }

    @Test
    fun `a paintable area a decorator would recognise`() {
        // 20 m2 room, standard ceiling, one door and one window: about 39 m2 of wall.
        val surfaces = SurfaceCalculator.compute(room, 2.4, listOf(door, window))
        assertTrue(
            surfaces.netWallArea.squareMetres in 38.0..40.0,
            "net wall area was ${surfaces.netWallArea.squareMetres}",
        )
    }

    @Test
    fun `more opening than wall clamps to zero rather than going negative`() {
        val absurd = List(40) { door }
        val surfaces = SurfaceCalculator.compute(room, 2.4, absurd)
        assertEquals(0.0, surfaces.netWallArea.squareMetres, 1e-12)
    }

    @Test
    fun `an unknown height gives no wall area and no volume, not a negative one`() {
        val surfaces = SurfaceCalculator.compute(room, ceilingHeight = -1.0)
        assertEquals(0.0, surfaces.grossWallArea.squareMetres, 1e-12)
        assertEquals(0.0, surfaces.volume.cubicMetres, 1e-12)
        assertEquals(20.0, surfaces.floorArea.squareMetres, 1e-9)
    }
}

class StandardOpeningTest {

    @Test
    fun `a standard door is a standard door`() {
        val opening = Opening.standard(OpeningKind.DOOR, wallLength = 4.0, ceilingHeight = 2.4)
        assertEquals(0.83, opening.width, 1e-9)
        assertEquals(2.04, opening.height, 1e-9)
        assertEquals(0.0, opening.sillHeight, 1e-9)
    }

    @Test
    fun `a window gets a sill`() {
        val opening = Opening.standard(OpeningKind.WINDOW, wallLength = 4.0, ceilingHeight = 2.4)
        assertTrue(opening.sillHeight > 0.5, "a window at floor level is a door")
    }

    @Test
    fun `openings are centred on their wall`() {
        val opening = Opening.standard(OpeningKind.DOOR, wallLength = 4.0, ceilingHeight = 2.4)
        val gapAfter = 4.0 - (opening.offset + opening.width)
        assertEquals(opening.offset, gapAfter, 1e-9)
    }

    @Test
    fun `a narrow wall gets a narrow opening rather than an invalid one`() {
        val opening = Opening.standard(OpeningKind.DOOR, wallLength = 0.6, ceilingHeight = 2.4)
        assertTrue(opening.fitsIn(0.6, 2.4), "does not fit: $opening")
    }

    @Test
    fun `a low ceiling shortens the opening rather than pushing it through`() {
        val opening = Opening.standard(OpeningKind.DOOR, wallLength = 4.0, ceilingHeight = 1.9)
        assertTrue(opening.fitsIn(4.0, 1.9), "does not fit: $opening")
        assertTrue(opening.height < 2.04)
    }

    @Test
    fun `every kind produces something that fits an ordinary wall`() {
        OpeningKind.entries.forEach { kind ->
            val opening = Opening.standard(kind, wallLength = 3.0, ceilingHeight = 2.4)
            assertTrue(opening.fitsIn(3.0, 2.4), "$kind does not fit: $opening")
        }
    }
}
