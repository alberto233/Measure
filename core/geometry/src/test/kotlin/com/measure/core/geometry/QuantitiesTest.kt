package com.measure.core.geometry

import com.measure.core.units.Area
import com.measure.core.units.Length
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 5 x 4: 20 m² floor, 18 m perimeter. At 2.4 m high that is 43.2 m² of wall. */
private val kitchen = Polygon(
    listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 4.0), Vec2(0.0, 4.0)),
)

/** 3 x 4: 12 m² floor, 14 m perimeter. At 2.4 m high that is 33.6 m² of wall. */
private val hall = Polygon(
    listOf(Vec2(0.0, 0.0), Vec2(3.0, 0.0), Vec2(3.0, 4.0), Vec2(0.0, 4.0)),
)

private fun quantity(name: String, polygon: Polygon, height: Double?, openings: List<Opening> = emptyList()) =
    RoomQuantity(
        name = name,
        floorArea = polygon.area,
        perimeter = polygon.perimeter,
        surfaces = height?.let { SurfaceCalculator.compute(polygon, it, openings) },
    )

class TakeoffTest {

    @Test
    fun `floors and walls add up across the plan`() {
        val takeoff = Takeoff(
            listOf(
                quantity("Kitchen", kitchen, 2.4),
                quantity("Hall", hall, 2.4),
            ),
        )

        assertEquals(32.0, takeoff.floorArea.squareMetres, 1e-9)
        assertEquals(32.0, takeoff.perimeter.metres, 1e-9)
        assertEquals(43.2 + 33.6, takeoff.netWallArea.squareMetres, 1e-9)
        assertEquals(20.0 * 2.4 + 12.0 * 2.4, takeoff.volume.cubicMetres, 1e-9)
        assertFalse(takeoff.wallsAreIncomplete)
    }

    /**
     * The case the whole design turns on.
     *
     * A room with no ceiling height still has a floor, and leaving it out of the floor total
     * would understate the flooring order. Its walls are unknown, and quietly counting them
     * as zero would understate the paint — with nothing on screen to say so, which is the
     * difference between an estimate and a wrong answer.
     */
    @Test
    fun `a room without a height counts for its floor and not its walls`() {
        val takeoff = Takeoff(
            listOf(
                quantity("Kitchen", kitchen, 2.4),
                quantity("Hall", hall, null),
            ),
        )

        assertEquals(32.0, takeoff.floorArea.squareMetres, 1e-9)
        assertEquals(43.2, takeoff.netWallArea.squareMetres, 1e-9)
        assertTrue(takeoff.wallsAreIncomplete)
        assertEquals(listOf("Hall"), takeoff.roomsWithoutHeight)
    }

    /** Ceilings do not depend on how high they are, so they are known for every room. */
    @Test
    fun `ceiling area covers rooms whose walls are unknown`() {
        val takeoff = Takeoff(
            listOf(
                quantity("Kitchen", kitchen, 2.4),
                quantity("Hall", hall, null),
            ),
        )

        assertEquals(32.0, takeoff.ceilingArea.squareMetres, 1e-9)
    }

    @Test
    fun `doors and windows come out of the wall total`() {
        val door = Opening(OpeningKind.DOOR, offset = 0.4, width = 0.83, height = 2.04)
        val takeoff = Takeoff(listOf(quantity("Kitchen", kitchen, 2.4, listOf(door))))

        assertEquals(door.area.squareMetres, takeoff.openingArea.squareMetres, 1e-9)
        assertEquals(43.2 - door.area.squareMetres, takeoff.netWallArea.squareMetres, 1e-9)
    }

    @Test
    fun `an empty plan totals nothing rather than failing`() {
        val takeoff = Takeoff(emptyList())

        assertTrue(takeoff.isEmpty)
        assertEquals(0.0, takeoff.floorArea.squareMetres, 1e-12)
        assertEquals(0.0, takeoff.netWallArea.squareMetres, 1e-12)
        assertFalse(takeoff.wallsAreIncomplete)
    }
}

class FlooringTest {

    @Test
    fun `waste is added to the floor area`() {
        assertEquals(22.0, Flooring.required(Area(20.0), 10).squareMetres, 1e-9)
        assertEquals(23.0, Flooring.required(Area(20.0), 15).squareMetres, 1e-9)
    }

    @Test
    fun `no waste is the area itself`() {
        assertEquals(20.0, Flooring.required(Area(20.0), 0).squareMetres, 1e-12)
    }

    /** A negative allowance would order less floor than there is floor. */
    @Test
    fun `a negative allowance is refused rather than subtracted`() {
        assertEquals(20.0, Flooring.required(Area(20.0), -25).squareMetres, 1e-12)
    }
}

class PaintingTest {

    @Test
    fun `two coats at ten square metres a litre`() {
        // 43.2 m² x 2 coats / 10 = 8.64 L, rounded up to a tenth.
        assertEquals(8.7, Painting.required(Area(43.2), coats = 2).litres, 1e-9)
    }

    @Test
    fun `coverage is the tin's number, not ours`() {
        assertEquals(4.4, Painting.required(Area(43.2), coats = 1, coveragePerLitre = 10.0).litres, 1e-9)
        assertEquals(3.1, Painting.required(Area(43.2), coats = 1, coveragePerLitre = 14.0).litres, 1e-9)
    }

    /** Always up: a tin short is a second trip and a batch that will not match. */
    @Test
    fun `a quantity is never rounded down`() {
        assertTrue(Painting.required(Area(10.01), coats = 1).litres >= 1.001)
    }

    @Test
    fun `nonsense inputs give nothing rather than an infinity`() {
        assertEquals(0.0, Painting.required(Area(43.2), coats = 0).litres, 1e-12)
        assertEquals(0.0, Painting.required(Area(43.2), coats = 2, coveragePerLitre = 0.0).litres, 1e-12)
        assertEquals(0.0, Painting.required(Area(0.0), coats = 2).litres, 1e-12)
    }
}
