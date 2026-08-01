package com.measure.core.geometry.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private var nextId = 0L

private fun plane(
    height: Double,
    area: Double,
    upward: Boolean = true,
    subsumed: Boolean = false,
) = PlaneObservation(nextId++, height, area, upward, subsumed)

class FloorSelectorTest {

    @Test
    fun `nothing to choose from`() {
        assertNull(FloorSelector.select(emptyList()))
        assertNull(FloorSelector.select(listOf(plane(0.0, 8.0, upward = false))))
        assertNull(FloorSelector.select(listOf(plane(0.0, 8.0, subsumed = true))))
    }

    @Test
    fun `a single floor is chosen as it stands`() {
        val floor = FloorSelector.select(listOf(plane(-1.4, 12.0)))
        assertNotNull(floor)
        assertEquals(-1.4, floor!!.height, 1e-9)
        assertEquals(12.0, floor.area, 1e-9)
        assertEquals(1, floor.planeCount)
        assertTrue(floor.isEstablished)
    }

    @Test
    fun `fragments of one floor are merged, however ARCore split them`() {
        // The characteristic ARCore behaviour: one physical floor reported as several
        // planes a centimetre or two apart, without any subsumedBy relationship.
        val floor = FloorSelector.select(
            listOf(
                plane(-1.40, 4.0),
                plane(-1.42, 3.0),
                plane(-1.39, 5.0),
            ),
        )!!

        assertEquals(3, floor.planeCount)
        assertEquals(12.0, floor.area, 1e-9)
        assertTrue(floor.height in -1.42..-1.39, "merged height ${floor.height}")
    }

    @Test
    fun `merging is area weighted, so a small stray fragment barely moves the level`() {
        val floor = FloorSelector.select(
            listOf(
                plane(-1.40, 20.0),
                plane(-1.45, 0.5),
            ),
        )!!
        assertEquals(-1.401, floor.height, 0.002)
    }

    @Test
    fun `surfaces further apart than the tolerance stay separate`() {
        val floor = FloorSelector.select(
            listOf(
                plane(-1.40, 10.0),
                plane(-0.65, 10.0),
            ),
        )!!
        assertEquals(1, floor.planeCount)
        assertEquals(-1.40, floor.height, 1e-9)
    }

    @Test
    fun `a table does not become the floor even when more of it has been seen`() {
        // The case that motivates preferring height over raw area: in a cluttered room
        // ARCore often has a cleaner fit on the table than on the carpet around it.
        val floor = FloorSelector.select(
            listOf(
                plane(-1.40, 6.0), // floor, partly occluded
                plane(-0.65, 9.0), // dining table, seen well
            ),
        )!!
        assertEquals(-1.40, floor.height, 1e-9)
    }

    @Test
    fun `but a scrap on the ground does not outvote a properly seen floor`() {
        val floor = FloorSelector.select(
            listOf(
                plane(-1.75, 0.3), // a sliver under a sofa, far too small to trust
                plane(-1.40, 14.0), // the actual floor
            ),
        )!!
        assertEquals(-1.40, floor.height, 1e-9)
    }

    @Test
    fun `equal areas are broken by choosing the lower surface`() {
        val floor = FloorSelector.select(
            listOf(
                plane(-0.40, 8.0),
                plane(-1.40, 8.0),
            ),
        )!!
        assertEquals(-1.40, floor.height, 1e-9)
    }

    @Test
    fun `a scrap of floor is not yet established`() {
        val floor = FloorSelector.select(listOf(plane(-1.4, 0.4)))!!
        assertFalse(floor.isEstablished, "a 0.4 m2 patch should not count as the floor")
    }

    @Test
    fun `ceilings and walls are ignored entirely`() {
        val floor = FloorSelector.select(
            listOf(
                plane(1.1, 30.0, upward = false), // ceiling
                plane(0.0, 20.0, upward = false), // wall
                plane(-1.4, 6.0),
            ),
        )!!
        assertEquals(-1.4, floor.height, 1e-9)
    }
}
