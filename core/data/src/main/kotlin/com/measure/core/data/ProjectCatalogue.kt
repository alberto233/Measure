package com.measure.core.data

import java.text.Normalizer
import java.util.Locale

/**
 * How the project list is ordered — docs/PRODUCT_PLAN.md M13.
 *
 * Three, not six. Every additional order is another control to read past on the way to the
 * plan someone came here for, and the list is the screen that exists to get out of.
 */
enum class ProjectSort {
    /** What you were last working on, which is nearly always what you want. */
    RECENT,
    NAME,
    /** Biggest first, because "the big job" is how people remember a plan by size. */
    LARGEST,
}

/**
 * Finding one plan among many — docs/PRODUCT_PLAN.md M13.
 *
 * Pure and separate from the screen so the matching rules can be tested, which matters more
 * than it looks: a search that quietly fails to match is indistinguishable from a plan that
 * is not there, and the user's conclusion is that the app lost their work.
 */
object ProjectCatalogue {

    /** Filters and orders in one pass, which is the only way the screen ever wants them. */
    fun arrange(
        projects: List<ProjectSummary>,
        query: String = "",
        sort: ProjectSort = ProjectSort.RECENT,
    ): List<ProjectSummary> = projects.filter { matches(it, query) }.sortedWith(comparator(sort))

    /**
     * Whether a plan answers to [query].
     *
     * Every word has to appear somewhere in the name or the reference, in any order. "ash
     * 3" finds "Plan 3" at "14 Ash Road" — which plain substring matching would not, since
     * that string appears nowhere in either field. Typing more words narrows rather than
     * breaks, which is how people expect search to behave without being told.
     */
    fun matches(project: ProjectSummary, query: String): Boolean {
        val words = fold(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true

        val haystack = fold("${project.name} ${project.reference}")
        return words.all { haystack.contains(it) }
    }

    fun comparator(sort: ProjectSort): Comparator<ProjectSummary> = when (sort) {
        ProjectSort.RECENT -> compareByDescending { it.updatedAt }
        // Ties broken by recency rather than left to the sort's own stability, so two
        // plans of the same size always come back in the same order.
        ProjectSort.LARGEST -> compareByDescending<ProjectSummary> { it.totalArea.squareMetres }
            .thenByDescending { it.updatedAt }

        ProjectSort.NAME -> Comparator { a, b -> compareNaturally(a.name, b.name) }
    }

    /**
     * Compares names the way a person reads them, so "Plan 2" comes before "Plan 10".
     *
     * Worth the code because of what the default names are. Sorting `Plan 1, Plan 10,
     * Plan 2` alphabetically is technically correct and looks like a bug to everyone who
     * sees it — and since the app names plans "Plan N" itself, that is the ordering most
     * users would get.
     */
    fun compareNaturally(left: String, right: String): Int {
        var i = 0
        var j = 0
        val a = fold(left)
        val b = fold(right)

        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                val startI = i
                val startJ = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++

                // Compared as numbers, and by length first so that arbitrarily long runs
                // of digits do not have to fit in a Long to be ordered correctly.
                val numberA = a.substring(startI, i).trimStart('0')
                val numberB = b.substring(startJ, j).trimStart('0')
                if (numberA.length != numberB.length) return numberA.length - numberB.length
                if (numberA != numberB) return numberA.compareTo(numberB)
            } else {
                if (a[i] != b[j]) return a[i].compareTo(b[j])
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    /**
     * Lower-cased and stripped of accents, so search ignores both.
     *
     * The accents are the load-bearing half. This app is being built and tested in Spanish,
     * where "Ático" is a word someone will genuinely type into the reference field and then
     * search for as "atico" — and a search that returns nothing reads as lost work rather
     * than as a missing accent.
     */
    private fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .filter { it.code !in COMBINING_MARKS }
            .trim()

    /** The Unicode block holding the accents that NFD splits off. */
    private val COMBINING_MARKS = 0x0300..0x036F
}
