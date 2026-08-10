package com.traza.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every English string has a Spanish one, in every module, and neither list has drifted.
 *
 * This is the test that keeps a translation alive. The failure it exists to catch is not a
 * mistranslation — nobody ships those by accident — it is the ordinary one: somebody adds a
 * string to a screen, ships it, and a Spanish user gets an English sentence in the middle of
 * their interface. Nothing else in the build notices. `lint`'s `MissingTranslation` check is
 * the same idea, but it only runs on the app's merged resources at release time and is
 * routinely disabled; this runs on every module in every test run.
 *
 * It reads the XML rather than `R.string`, deliberately. Reflecting over `R` would tell us
 * which identifiers exist after merging, which is exactly the point at which the two locales
 * have already been flattened into one list and the difference we are looking for is gone.
 *
 * Untranslatable strings — a brand name, a symbol — are exempted the standard way, with
 * `translatable="false"`, so the exemption lives beside the string instead of in this file.
 */
class TranslationTest {

    /**
     * Every listed module has both files, and the list itself is not quietly rotting.
     *
     * The second half is the one that has already caught something. An earlier version
     * skipped any module with no English file, on the reasoning that a module with no text
     * needs no translation — and then a module's resources went missing and the test went on
     * passing, because "has no strings" and "lost its strings" look identical from here.
     * Every module on that list ships text; a missing file is a fault, not an exemption.
     */
    @Test
    fun `every module has its strings in both languages`() {
        for (module in MODULES) {
            assertTrue(
                "$module has no English strings at all. Either it lost them, or it no " +
                    "longer ships text and should come off the list in this test.",
                englishFile(module).exists(),
            )
            assertTrue(
                "$module has English strings and no Spanish ones. A module that ships text " +
                    "ships it in every language the app claims to support.",
                spanishFile(module).exists(),
            )
        }
    }

    /**
     * Nothing declares a string the code never reads.
     *
     * The other half of the same failure: a screen can be reverted to hardcoded text and
     * leave its `strings.xml` behind, at which point both languages agree perfectly and the
     * app is in neither of them. Matched by name against the source rather than through `R`,
     * because `R` is generated from the resources and would agree with itself.
     */
    @Test
    fun `every string is referenced from somewhere`() {
        val sources = MODULES.flatMap { module ->
            File(root, "$module/src/main").walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { it.readText() }
                .toList()
        } + MODULES.flatMap { module ->
            File(root, "$module/src/main").walkTopDown()
                .filter { it.isFile && it.extension == "xml" && it.parentFile.name !in RESOURCE_DIRS }
                .map { it.readText() }
                .toList()
        }

        val unused = modulesWithStrings().flatMap { module ->
            names(englishFile(module), translatableOnly = false).filter { name ->
                sources.none { it.contains("R.string.$name") || it.contains("@string/$name") }
            }
        }

        assertEquals(
            "These strings are declared and never read. Either something stopped using " +
                "them, or something stopped reading them and went back to a literal.",
            emptyList<String>(),
            unused.sorted(),
        )
    }

    @Test
    fun `no string is left untranslated`() {
        for (module in modulesWithStrings()) {
            val english = names(englishFile(module), translatableOnly = true)
            val spanish = names(spanishFile(module), translatableOnly = false)

            val missing = (english - spanish).sorted()
            val orphaned = (spanish - english).sorted()

            assertEquals(
                "$module: these strings exist in English and not in Spanish. A Spanish user " +
                    "sees the English text for each of them.",
                emptyList<String>(),
                missing,
            )
            assertEquals(
                "$module: these strings exist in Spanish and not in English, so nothing can " +
                    "ever display them. They are usually the remains of a rename.",
                emptyList<String>(),
                orphaned,
            )
        }
    }

    /**
     * A translated string that still reads as English is the failure this catches.
     *
     * Copy-pasting the English file and translating most of it is the normal way a
     * translation gets made, and the lines left behind are invisible in a diff of two files
     * that are supposed to look alike. Short strings are exempt because plenty of them are
     * legitimately identical — "Total", "PDF", a unit symbol — and because a two-word label
     * that survives translation is not evidence of anything.
     */
    @Test
    fun `no long string was left in English`() {
        val untranslated = mutableListOf<String>()
        for (module in modulesWithStrings()) {
            val english = values(englishFile(module))
            val spanish = values(spanishFile(module))
            for ((name, text) in english) {
                if (text.length >= 40 && spanish[name] == text) {
                    untranslated += "$module/$name"
                }
            }
        }

        assertEquals(
            "These strings are word-for-word identical in both languages, which for a " +
                "sentence means it was never translated.",
            emptyList<String>(),
            untranslated.sorted(),
        )
    }

    private fun modulesWithStrings(): List<String> =
        MODULES.filter { englishFile(it).exists() }

    private fun englishFile(module: String) = File(root, "$module/src/main/res/values/strings.xml")

    private fun spanishFile(module: String) = File(root, "$module/src/main/res/values-es/strings.xml")

    /** @param translatableOnly drops the strings marked `translatable="false"`. */
    private fun names(file: File, translatableOnly: Boolean): Set<String> =
        entries(file)
            .filter { !translatableOnly || it.getAttribute("translatable") != "false" }
            .map { it.getAttribute("name") }
            .toSet()

    private fun values(file: File): Map<String, String> =
        entries(file)
            .filter { it.getAttribute("translatable") != "false" }
            .associate { it.getAttribute("name") to it.textContent.trim() }

    private fun entries(file: File): List<org.w3c.dom.Element> {
        if (!file.exists()) return emptyList()
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
    }

    private companion object {
        /**
         * The repository root, found by walking up rather than by counting `..` segments,
         * because a Gradle test's working directory is the module directory today and there
         * is no rule that says it always will be.
         */
        val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }

        /**
         * Listed rather than discovered. A glob would silently stop covering a module the
         * day somebody moved it, and the whole value of this test is that it notices things
         * nobody remembered to tell it.
         */
        /** Resource value directories, whose XML is the declaration rather than a use. */
        val RESOURCE_DIRS = setOf("values", "values-es")

        val MODULES = listOf(
            "app",
            "core/designsystem",
            "feature/capture",
            "feature/editor",
            "feature/export",
            "feature/onboarding",
            "feature/projects",
        )
    }
}
