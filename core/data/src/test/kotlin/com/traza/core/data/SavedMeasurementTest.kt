package com.traza.core.data

import com.traza.core.geometry.Vec3
import com.traza.core.geometry.capture.MeasurementMode
import com.traza.core.units.Length
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SavedMeasurementTest {

    private fun measurement(from: Vec3, to: Vec3, mode: MeasurementMode = MeasurementMode.FREE) =
        SavedMeasurement(
            id = 1,
            mode = mode,
            from = from,
            to = to,
            length = Length(from.distanceTo(to)),
            sigma = Length(0.01),
            label = null,
            createdAt = 0,
        )

    @Test
    fun `a room height has no length on the plan`() {
        val height = measurement(
            from = Vec3(1.0, 0.0, -2.0),
            to = Vec3(1.0, 2.45, -2.0),
            mode = MeasurementMode.VERTICAL,
        )
        assertTrue(height.isVerticalOnPlan)
    }

    @Test
    fun `a wall length across the floor does`() {
        assertFalse(measurement(Vec3(0.0, 0.0, 0.0), Vec3(3.6, 0.0, 0.0)).isVerticalOnPlan)
    }

    @Test
    fun `a short horizontal measurement is still a line, not a height`() {
        // A doorframe measured across: only 8 cm on the plan, but all of it is on the
        // plan, so it draws as the short line it is.
        assertFalse(measurement(Vec3(0.0, 1.0, 0.0), Vec3(0.08, 1.0, 0.0)).isVerticalOnPlan)
    }

    @Test
    fun `a mostly vertical free measurement counts as a height`() {
        // Taken up a wall by eye rather than in plumb mode: 2.4 m of rise with 20 cm of
        // wander. The mode says FREE; only the geometry says what it is.
        val leaning = measurement(Vec3(0.0, 0.1, 0.0), Vec3(0.2, 2.5, 0.0))
        assertTrue(leaning.isVerticalOnPlan)
    }

    @Test
    fun `a diagonal across a room is not a height`() {
        assertFalse(measurement(Vec3(0.0, 0.0, 0.0), Vec3(3.0, 2.4, -3.0)).isVerticalOnPlan)
    }
}
