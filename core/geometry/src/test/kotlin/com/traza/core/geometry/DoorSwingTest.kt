package com.traza.core.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * How a door is hung.
 *
 * Small, and worth having because the default is load-bearing across an upgrade: every
 * door captured before v8 has an empty string stored for its swing, and if that ever
 * stopped reading as the hanging the app used to draw, every existing plan would silently
 * rotate its doors the first time someone opened it after updating.
 */
class DoorSwingTest {

    @Test
    @DisplayName("the default is what the app drew before doors could be hung")
    fun `default matches the old drawing`() {
        // Hinged at the jamb the offset is measured from, opening into the room.
        assertEquals(false, DoorSwing.HINGE_NEAR_OPENS_IN.hingeAtFarJamb)
        assertEquals(false, DoorSwing.HINGE_NEAR_OPENS_IN.opensOut)
        assertEquals(DoorSwing.HINGE_NEAR_OPENS_IN, Opening(OpeningKind.DOOR, 0.5, 0.83, 2.04).swing)
    }

    @Test
    @DisplayName("an unreadable stored value falls back rather than throwing")
    fun `parse is tolerant`() {
        // The empty string is what MIGRATION_7_8 back-fills, and is the case that matters.
        assertEquals(DoorSwing.HINGE_NEAR_OPENS_IN, DoorSwing.parse(""))
        assertEquals(DoorSwing.HINGE_NEAR_OPENS_IN, DoorSwing.parse(null))
        // A name from a future version this build has never heard of.
        assertEquals(DoorSwing.HINGE_NEAR_OPENS_IN, DoorSwing.parse("HINGE_SIDEWAYS_OPENS_UP"))
        assertEquals(DoorSwing.HINGE_FAR_OPENS_OUT, DoorSwing.parse("HINGE_FAR_OPENS_OUT"))
    }

    @Test
    @DisplayName("each half flips independently, and every combination exists")
    fun `with flips one choice at a time`() {
        for (swing in DoorSwing.entries) {
            val hinge = swing.with(hingeAtFarJamb = !swing.hingeAtFarJamb)
            assertEquals(!swing.hingeAtFarJamb, hinge.hingeAtFarJamb)
            assertEquals(swing.opensOut, hinge.opensOut, "flipping the hinge changed the swing")

            val opens = swing.with(opensOut = !swing.opensOut)
            assertEquals(!swing.opensOut, opens.opensOut)
            assertEquals(swing.hingeAtFarJamb, opens.hingeAtFarJamb, "flipping the swing changed the hinge")
        }
    }

    @Test
    @DisplayName("all four hangings are distinct")
    fun `four distinct hangings`() {
        assertEquals(4, DoorSwing.entries.map { it.hingeAtFarJamb to it.opensOut }.toSet().size)
    }
}
