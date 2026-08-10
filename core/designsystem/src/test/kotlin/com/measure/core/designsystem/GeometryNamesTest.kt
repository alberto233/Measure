package com.measure.core.designsystem

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.measure.core.geometry.DoorSwing
import com.measure.core.geometry.OpeningKind
import com.measure.core.geometry.capture.HitSource
import com.measure.core.geometry.capture.MeasurementMode
import com.measure.core.geometry.capture.RangeAdvice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every geometry value has a name, and no two of them share one.
 *
 * The `when` blocks in `GeometryNames` are exhaustive, so the compiler already guarantees a
 * mapping exists for every entry — that is the half of this that used to be a test in
 * `:core:geometry` and is now a compile error instead. What the compiler cannot see is
 * whether the resource on the other end of the mapping says anything, or says the same thing
 * as its neighbour: two door swings pointing at the same string compiles perfectly and leaves
 * the user with two identical options and no way to tell them apart.
 */
@RunWith(RobolectricTestRunner::class)
class GeometryNamesTest {

    private val resources
        get() = ApplicationProvider.getApplicationContext<Application>().resources

    private fun assertDistinctAndPresent(what: String, ids: List<Int>) {
        val names = ids.map { resources.getString(it) }
        names.forEach { assertTrue("$what has a blank name.", it.isNotBlank()) }
        assertEquals(
            "$what has two entries with the same name: $names",
            names.size,
            names.toSet().size,
        )
    }

    @Test
    fun `measurement modes are named and distinct`() {
        assertDistinctAndPresent("A measurement mode", MeasurementMode.entries.map { it.labelRes() })
        assertDistinctAndPresent("A mode hint", MeasurementMode.entries.map { it.hintRes() })
    }

    @Test
    fun `openings are named and distinct`() {
        assertDistinctAndPresent("An opening kind", OpeningKind.entries.map { it.labelRes() })
    }

    /** Four ways to hang a door, and the user picks between them by reading these. */
    @Test
    fun `door swings are named and distinct`() {
        assertDistinctAndPresent("A door swing", DoorSwing.entries.map { it.labelRes() })
    }

    @Test
    fun `hit sources are named and distinct`() {
        assertDistinctAndPresent("A hit source", HitSource.entries.map { it.labelRes() })
    }

    /**
     * The comfortable band says nothing, and every other band says something different.
     *
     * The silence is the design: an indicator that is always lit stops being read, so
     * `IDEAL` maps to null on purpose. That is easy to "fix" by someone adding a reassuring
     * message, which is why it is pinned here rather than left as a comment.
     */
    @Test
    fun `range advice is silent only in the comfortable band`() {
        assertNull("The comfortable range is meant to say nothing.", RangeAdvice.IDEAL.messageRes())

        val warnings = RangeAdvice.entries.filter { it != RangeAdvice.IDEAL }
        assertDistinctAndPresent("Range advice", warnings.map { it.messageRes()!! })
    }
}
