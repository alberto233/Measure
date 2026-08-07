package com.measure.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.measure.core.designsystem.MeasureSheet
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sheet survives being collapsed.
 *
 * A field test found it dissolving: collapse the panel on the plan view and it kept
 * shrinking, over about a second, until there was nothing left to grab and the panel could
 * not be reopened at all.
 *
 * The cause was a measurement that fed back on itself. The grip row was measured with
 * `onSizeChanged` while being a direct child of a Column with an explicit height, so it
 * reported what the sheet was *giving* it rather than what it wanted. Collapsing shrank the
 * sheet towards the header's height, which squeezed the header, which lowered the target,
 * which shrank the sheet — converging on one pixel.
 *
 * Tested here rather than in `:core:designsystem`, which has no test harness at all, and
 * at the level the fault was actually met: a sheet on a screen, collapsed by a caller.
 * The assertion is deliberately about *height* rather than about the internals, because
 * the internals are exactly what changed to fix it and a test coupled to them would have
 * to be rewritten by the next person who touches the layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class MeasureSheetTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a collapsed sheet keeps something to grab`() {
        // Hoisted outside composition, the way EditorScreen hoists it. An earlier version
        // of this test flipped the flag from inside the composable body; the write never
        // took, the sheet stayed open at full height, and the test passed against the
        // broken sheet as happily as against the fixed one.
        val expanded = mutableStateOf(true)

        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                MeasureSheet(
                    expanded = expanded.value,
                    onExpandedChange = { expanded.value = it },
                    modifier = Modifier.align(Alignment.BottomCenter).testTag(SHEET),
                ) {
                    // Tall enough that open and collapsed are unmistakably different, so a
                    // sheet stuck open would not pass this either.
                    Box(Modifier.height(300.dp)) { Text("panel") }
                }
            }
        }

        compose.mainClock.advanceTimeBy(1_000)
        val open = compose.onNodeWithTag(SHEET).fetchSemanticsNode().size.height

        expanded.value = false
        // Well past the settle. The fault needed repeated re-targeting to converge, so a
        // test that stopped at the first frame would have watched it look healthy.
        compose.mainClock.advanceTimeBy(5_000)
        val collapsed = compose.onNodeWithTag(SHEET).fetchSemanticsNode().size.height

        assertTrue(
            "The sheet did not collapse at all, so this proves nothing: " +
                "open=" + open + "px collapsed=" + collapsed + "px",
            collapsed < open / 2,
        )
        compose.onNodeWithTag(SHEET).assertHeightIsAtLeast(24.dp)
    }

    private companion object {
        const val SHEET = "measure-sheet"
    }
}
