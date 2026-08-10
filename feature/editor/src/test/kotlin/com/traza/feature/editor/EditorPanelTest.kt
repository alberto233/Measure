package com.traza.feature.editor

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.traza.core.data.MeasureData
import com.traza.core.data.MeasureDatabase
import com.traza.core.data.MeasureRepository
import com.traza.core.geometry.CapturedCorner
import com.traza.core.geometry.RoomCapture
import com.traza.core.geometry.RoomSolver
import com.traza.core.geometry.Vec2
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The editor's panels, rendered against a real database.
 *
 * These exist because of a specific fault that reached the user three times and that no
 * test in this project could have caught. `viewModel.roomById()` read `project.value` — an
 * ordinary field, invisible to Compose — so a panel recorded no dependency on the model,
 * and with strong skipping on, `WallPanel(viewModel, selection)` was skipped outright when
 * its two parameters had not changed. Adding a door wrote the row and changed nothing on
 * screen. It looked exactly like a button that did not work.
 *
 * The geometry core has 247 tests and every one of them would have passed. The gap was
 * never the maths; it was that nothing ever rendered a screen and pressed anything.
 *
 * Robolectric rather than an emulator, so these run on the JVM in the fast CI job. A real
 * in-memory Room database rather than a fake repository, because the fault lived in the
 * seam between a write and what the screen did about it, and a fake would have modelled
 * that seam as working.
 */
@RunWith(RobolectricTestRunner::class)
// A real handset's dimensions. Robolectric's default display is small enough that a panel
// pinned to the bottom of a scrolling screen can sit outside the viewport, which would make
// these fail for a reason that has nothing to do with what they test.
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class EditorPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var database: MeasureDatabase
    private lateinit var repository: MeasureRepository

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        // Room's queries and its invalidation tracker run inline, on whichever thread asks.
        //
        // Load-bearing, and the reason two attempts at this failed. Compose's test rule
        // drives a **virtual** clock, so `waitUntil` can spin through its whole timeout in
        // a few milliseconds of real time — which is why raising that timeout to a minute
        // made things worse rather than better. Left on its own executor, Room delivers the
        // project from a real background thread on real time the test never spends, and
        // whether the flow arrives before the clock runs out is a race.
        //
        // Inline, there is no other thread to wait for and no race to lose.
        val inline = java.util.concurrent.Executor { it.run() }
        database = Room.inMemoryDatabaseBuilder(application, MeasureDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        repository = MeasureRepository(database)
        MeasureData.useForTesting(repository)
    }

    @After
    fun tearDown() {
        MeasureData.useForTesting(null)
        // The database is deliberately **not** closed.
        //
        // A JUnit rule wraps @Before/@Test/@After, so the Compose rule tears the
        // composition down *after* this runs — closing here would pull the database out
        // from under a live `EditorViewModel`, whose init block collects the project for
        // the whole life of the view model.
        //
        // Hygiene rather than a diagnosis: an earlier version of this comment claimed the
        // close was what made a test time out, and that was wrong — removing it moved
        // which test failed instead of fixing anything. The real cause was the executor,
        // above. Nothing needs closing either way: each test builds its own in-memory
        // database, and an in-memory database is gone when the process is.
    }

    /** A four-corner room, five by four, saved into a fresh project. Returns both ids. */
    private fun seedRoom(): Pair<Long, Long> = runBlocking {
        val projectId = repository.createProject("Test plan")
        val corners = listOf(
            Vec2(0.0, 0.0),
            Vec2(5.0, 0.0),
            Vec2(5.0, 4.0),
            Vec2(0.0, 4.0),
        )
        val solution = RoomSolver.solve(RoomCapture(corners.map { CapturedCorner(it, 0.02) }))
        val roomId = repository.saveRoom(
            projectId = projectId,
            name = "Kitchen",
            solution = solution,
            measured = corners,
            sigmas = corners.map { 0.02 },
            captureSession = "test-session",
        )
        projectId to roomId
    }

    /**
     * Reading is a comparison as often as it is a lookup.
     *
     * A second tap used to throw the first selection away, so "do these runs add up to the
     * wall opposite" — the question people are actually asking when they tap twice — could
     * not be asked at all.
     */
    @Test
    fun `selecting a second run adds to the first rather than replacing it`() {
        val (projectId, _) = seedRoom()
        val viewModel = editor(projectId)
        val chains = viewModel.dimensionChains()
        assertTrue("The fixture needs at least two dimension chains.", chains.size >= 2)

        viewModel.focusOn(MeasureFocus.Dimension(0, 0))
        viewModel.focusOn(MeasureFocus.Dimension(1, 0))

        val lengths = viewModel.selectedLengths()
        assertEquals(2, lengths.size)
        assertEquals(
            chains[0].segments[0].length + chains[1].segments[0].length,
            lengths.sum(),
            1e-9,
        )
    }

    /** Tapping a selected run again is how a comparison is narrowed. */
    @Test
    fun `tapping a selected run again removes it`() {
        val (projectId, _) = seedRoom()
        val viewModel = editor(projectId)

        viewModel.focusOn(MeasureFocus.Dimension(0, 0))
        viewModel.focusOn(MeasureFocus.Dimension(1, 0))
        viewModel.focusOn(MeasureFocus.Dimension(1, 0))

        assertEquals(setOf(MeasureFocus.Dimension(0, 0)), viewModel.focuses)
    }

    /**
     * With exactly one thing selected the detailed readout still applies.
     *
     * `focus` is derived from the set rather than stored, and a derivation that reported
     * something while several were selected would put a readout describing "the marked
     * corners" over a total covering five of them.
     */
    @Test
    fun `the single-subject readout only applies to a single selection`() {
        val (projectId, _) = seedRoom()
        val viewModel = editor(projectId)

        viewModel.focusOn(MeasureFocus.Dimension(0, 0))
        assertEquals(MeasureFocus.Dimension(0, 0), viewModel.focus)

        viewModel.focusOn(MeasureFocus.Dimension(1, 0))
        assertEquals(MeasureFocus.None, viewModel.focus)

        viewModel.clearFocus()
        assertEquals(MeasureFocus.None, viewModel.focus)
        assertTrue(viewModel.selectedLengths().isEmpty())
    }


    private fun editor(projectId: Long): EditorViewModel {
        val viewModel = EditorViewModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent {
            EditorScreen(
                projectId = projectId,
                onBack = {},
                onAddRoom = {},
                viewModel = viewModel,
            )
        }
        // The project arrives from the database asynchronously; nothing below means
        // anything until it has.
        //
        // Longer than the assertions use, to absorb class loading on the first test of a
        // run. It is not there to wait for the database — see the executor in setUp; a
        // timeout cannot fix a race against a clock that is not real.
        compose.waitUntil(LOAD_TIMEOUT_MS) { viewModel.current?.rooms?.isNotEmpty() == true }
        return viewModel
    }

    /**
     * The harness itself.
     *
     * Deliberately trivial and deliberately first: it separates "Robolectric, Compose and
     * the repository seam are wired up" from "the editor behaves", so a failure says which.
     */
    @Test
    fun `the editor renders a saved room`() {
        val (projectId, roomId) = seedRoom()
        val viewModel = editor(projectId)

        assertTrue(viewModel.current?.rooms?.size == 1)
        compose.onNodeWithText("Test plan", ignoreCase = true).assertIsDisplayed()

        // The room's name is not on the plan. The canvas draws geometry, and the name lives
        // in the room's own panel — which this asserted without checking first, and which is
        // the sort of guess these tests exist to stop being made about the interface.
        viewModel.select(Selection.Room(roomId))
        compose.onNodeWithText("Kitchen", ignoreCase = true).assertExists()
    }

    /**
     * The regression. Adding a door has to change the panel that added it.
     *
     * Driven through the view model's selection rather than by tapping the plan, because
     * hitting a wall on the canvas means reproducing the camera transform — which would
     * make this a test of the test's arithmetic. The button and the assertion are both real
     * interface: this fails on the code as it was, and passes on the code as it is.
     */
    @Test
    fun `adding a door offers to remove it, without reselecting the wall`() {
        val (projectId, roomId) = seedRoom()
        val viewModel = editor(projectId)

        viewModel.select(Selection.Wall(roomId, 0))
        compose.onNodeWithText("No doors or windows", ignoreCase = true).assertExists()

        compose.onNodeWithText("+ Door", ignoreCase = true).performClick()

        // Nothing is touched in between. Before the fix the panel went on saying "No doors
        // or windows" until the selection changed, which is what sent the user hunting for
        // a way to make the button work.
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText("Remove", ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Remove", ignoreCase = true).assertCountEquals(1)
        compose.onNodeWithText("1 door", ignoreCase = true).assertExists()
    }

    /** And the same for removing one: the panel has to go back to saying there are none. */
    @Test
    fun `removing a door updates the panel too`() {
        val (projectId, roomId) = seedRoom()
        val viewModel = editor(projectId)

        viewModel.select(Selection.Wall(roomId, 0))
        compose.onNodeWithText("+ Door", ignoreCase = true).performClick()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText("Remove", ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Remove", ignoreCase = true).performClick()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText("No doors or windows", ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("No doors or windows", ignoreCase = true).assertExists()
    }

    private companion object {
        /** Once the screen is up, an answer is either quick or wrong. */
        const val TIMEOUT_MS = 5_000L

        /** Class loading on the first test of a run — Robolectric, Room and Compose. */
        const val LOAD_TIMEOUT_MS = 20_000L
    }
}
