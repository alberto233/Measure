package com.measure.core.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProjectNamingTest {

    @Test
    fun `the first name is one`() {
        assertEquals("Plan 1", ProjectNaming.nextProjectName(emptyList()))
        assertEquals("Room 1", ProjectNaming.nextRoomName(emptyList()))
    }

    @Test
    fun `numbering continues past what exists`() {
        assertEquals("Plan 3", ProjectNaming.nextProjectName(listOf("Plan 1", "Plan 2")))
    }

    @Test
    fun `a deleted name is reused rather than leaving a hole`() {
        assertEquals("Plan 2", ProjectNaming.nextProjectName(listOf("Plan 1", "Plan 3")))
    }

    @Test
    fun `names the user chose are ignored, not parsed`() {
        assertEquals(
            "Plan 1",
            ProjectNaming.nextProjectName(listOf("Kitchen", "Mum's flat", "Plan B", "Planning 4")),
        )
    }

    @Test
    fun `order does not matter and duplicates do not confuse it`() {
        assertEquals(
            "Plan 3",
            ProjectNaming.nextProjectName(listOf("Plan 2", "Plan 1", "Plan 2")),
        )
    }

    @Test
    fun `nonsense suffixes are not counted`() {
        assertEquals(
            "Plan 1",
            ProjectNaming.nextProjectName(listOf("Plan", "Plan x", "Plan -1", "Plan 0")),
        )
    }

    @Test
    fun `surrounding whitespace does not hide a number`() {
        assertEquals("Plan 2", ProjectNaming.nextProjectName(listOf("  Plan 1  ")))
    }
}
