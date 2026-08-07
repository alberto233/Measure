package com.measure.core.data

import com.measure.core.units.Area
import com.measure.core.units.UnitSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectCatalogueTest {

    private fun project(
        id: Long,
        name: String,
        reference: String = "",
        updatedAt: Long = 0,
        area: Double = 0.0,
    ) = ProjectSummary(
        id = id,
        name = name,
        reference = reference,
        updatedAt = updatedAt,
        unitSystem = UnitSystem.METRIC,
        roomCount = 1,
        measurementCount = 0,
        totalArea = Area(area),
        outlines = emptyList(),
        measurementLines = emptyList(),
        soleMeasurement = null,
    )

    // --- searching -----------------------------------------------------------------------

    @Test
    fun `an empty query matches everything`() {
        val all = listOf(project(1, "Plan 1"), project(2, "Plan 2"))
        assertEquals(2, ProjectCatalogue.arrange(all, query = "  ").size)
    }

    @Test
    fun `the reference is searched as well as the name`() {
        val plan = project(1, "Plan 3", reference = "14 Ash Road, Mrs Okafor")

        assertTrue(ProjectCatalogue.matches(plan, "ash"))
        assertTrue(ProjectCatalogue.matches(plan, "okafor"))
        assertFalse(ProjectCatalogue.matches(plan, "birch"))
    }

    @Test
    fun `words may come in any order and from either field`() {
        // The whole reason for splitting the query: this string appears in neither field,
        // and substring matching would find nothing while the user watched a plan they can
        // see on screen fail to be found.
        val plan = project(1, "Plan 3", reference = "14 Ash Road")
        assertTrue(ProjectCatalogue.matches(plan, "ash 3"))
        assertTrue(ProjectCatalogue.matches(plan, "3 ash"))
    }

    @Test
    fun `more words narrow rather than widen`() {
        val plan = project(1, "Plan 3", reference = "14 Ash Road")
        assertTrue(ProjectCatalogue.matches(plan, "ash road"))
        assertFalse(ProjectCatalogue.matches(plan, "ash road birch"))
    }

    @Test
    fun `search ignores case and accents`() {
        val plan = project(1, "Ático", reference = "Señora Muñoz")

        assertTrue(ProjectCatalogue.matches(plan, "atico"))
        assertTrue(ProjectCatalogue.matches(plan, "ATICO"))
        assertTrue(ProjectCatalogue.matches(plan, "munoz"))
        // And the other direction: typed with the accent, stored without.
        assertTrue(ProjectCatalogue.matches(project(2, "Atico"), "ático"))
    }

    // --- ordering ------------------------------------------------------------------------

    @Test
    fun `recent is newest first`() {
        val ordered = ProjectCatalogue.arrange(
            listOf(project(1, "A", updatedAt = 100), project(2, "B", updatedAt = 300)),
            sort = ProjectSort.RECENT,
        )
        assertEquals(listOf(2L, 1L), ordered.map { it.id })
    }

    @Test
    fun `name sorting counts, so Plan 2 comes before Plan 10`() {
        // The app names plans "Plan N" itself, so alphabetical ordering is what most users
        // would actually see — and it looks like a bug to everyone who sees it.
        val ordered = ProjectCatalogue.arrange(
            listOf(project(1, "Plan 10"), project(2, "Plan 2"), project(3, "Plan 1")),
            sort = ProjectSort.NAME,
        )
        assertEquals(listOf("Plan 1", "Plan 2", "Plan 10"), ordered.map { it.name })
    }

    @Test
    fun `natural ordering handles long numbers and leading zeros`() {
        assertTrue(ProjectCatalogue.compareNaturally("Flat 007", "Flat 8") < 0)
        assertTrue(ProjectCatalogue.compareNaturally("Flat 9", "Flat 10") < 0)
        assertTrue(
            ProjectCatalogue.compareNaturally("Flat 99999999999999999999", "Flat 100000000000000000000") < 0,
            "a number too long for a Long must still order correctly",
        )
    }

    @Test
    fun `name sorting is stable for identical names`() {
        assertEquals(0, ProjectCatalogue.compareNaturally("Plan 4", "plan 4"))
    }

    @Test
    fun `size sorting is largest first, ties broken by recency`() {
        val ordered = ProjectCatalogue.arrange(
            listOf(
                project(1, "A", area = 20.0, updatedAt = 100),
                project(2, "B", area = 55.0),
                project(3, "C", area = 20.0, updatedAt = 500),
            ),
            sort = ProjectSort.LARGEST,
        )
        assertEquals(listOf(2L, 3L, 1L), ordered.map { it.id })
    }

    @Test
    fun `filtering and ordering happen together`() {
        val ordered = ProjectCatalogue.arrange(
            listOf(
                project(1, "Plan 10", reference = "Ash Road"),
                project(2, "Plan 2", reference = "Birch Lane"),
                project(3, "Plan 1", reference = "Ash Road"),
            ),
            query = "ash",
            sort = ProjectSort.NAME,
        )
        assertEquals(listOf("Plan 1", "Plan 10"), ordered.map { it.name })
    }
}
