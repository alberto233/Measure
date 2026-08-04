package com.measure.core.geometry.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LoopClosureTest {

    @Test
    fun `a room cannot close before it encloses anything`() {
        assertEquals(ClosingIntent.ADD_CORNER, LoopClosure.classify(cornerCount = 0, distanceToStart = null))
        assertEquals(ClosingIntent.ADD_CORNER, LoopClosure.classify(cornerCount = 2, distanceToStart = 0.0))
    }

    @Test
    fun `landing back on the first corner closes the loop`() {
        assertEquals(ClosingIntent.CLOSE_LOOP, LoopClosure.classify(3, 0.0))
        assertEquals(ClosingIntent.CLOSE_LOOP, LoopClosure.classify(4, 0.1))
    }

    @Test
    fun `a corner a foot from the start is a corner, not a close`() {
        // The case this exists for: an alcove or a fitted unit beside the doorway the
        // walk began at. At the old half-metre radius this tap ended the room and the
        // corner was lost, and the difference was fed to the solver as drift.
        assertEquals(ClosingIntent.APPROACHING_START, LoopClosure.classify(4, 0.30))
        assertEquals(ClosingIntent.APPROACHING_START, LoopClosure.classify(4, 0.60))
    }

    @Test
    fun `well away from the start there is nothing to say`() {
        assertEquals(ClosingIntent.ADD_CORNER, LoopClosure.classify(4, 1.5))
    }

    @Test
    fun `the bands meet exactly, so no distance falls between them`() {
        assertEquals(ClosingIntent.CLOSE_LOOP, LoopClosure.classify(3, LoopClosure.CLOSING_RADIUS_METRES))
        assertEquals(
            ClosingIntent.APPROACHING_START,
            LoopClosure.classify(3, LoopClosure.CLOSING_RADIUS_METRES + 1e-9),
        )
        assertEquals(
            ClosingIntent.APPROACHING_START,
            LoopClosure.classify(3, LoopClosure.APPROACH_RADIUS_METRES),
        )
        assertEquals(
            ClosingIntent.ADD_CORNER,
            LoopClosure.classify(3, LoopClosure.APPROACH_RADIUS_METRES + 1e-9),
        )
    }
}
