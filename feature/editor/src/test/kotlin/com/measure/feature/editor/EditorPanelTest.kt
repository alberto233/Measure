package com.measure.feature.editor

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.measure.core.data.MeasureData
import com.measure.core.data.MeasureDatabase
import com.measure.core.data.MeasureRepository
import com.measure.core.geometry.CapturedCorner
import com.measure.core.geometry.RoomCapture
import com.measure.core.geometry.RoomSolver
import com.measure.core.geometry.Vec2
import kotlinx.coroutines.runBlocking
import org.junit.After
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
        database = Room.inMemoryDatabaseBuilder(application, MeasureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MeasureRepository(database)
        MeasureData.useForTesting(repository)
    }

    @After
    fun tearDown() {
        MeasureData.useForTesting(null)
        database.close()
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
        compose.waitUntil(TIMEOUT_MS) { viewModel.current?.rooms?.isNotEmpty() == true }
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
        val (projectId, _) = seedRoom()
        val viewModel = editor(projectId)

        assertTrue(viewModel.current?.rooms?.size == 1)
        compose.onNodeWithText("Test plan").assertIsDisplayed()
        compose.onNodeWithText("Kitchen").assertExists()
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
        compose.onNodeWithText("No doors or windows").assertExists()

        compose.onNodeWithText("+ Door").performClick()

        // Nothing is touched in between. Before the fix the panel went on saying "No doors
        // or windows" until the selection changed, which is what sent the user hunting for
        // a way to make the button work.
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText("Remove").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("Remove").assertCountEquals(1)
        compose.onNodeWithText("1 door").assertExists()
    }

    /** And the same for removing one: the panel has to go back to saying there are none. */
    @Test
    fun `removing a door updates the panel too`() {
        val (projectId, roomId) = seedRoom()
        val viewModel = editor(projectId)

        viewModel.select(Selection.Wall(roomId, 0))
        compose.onNodeWithText("+ Door").performClick()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText("Remove").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Remove").performClick()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText("No doors or windows").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("No doors or windows").assertExists()
    }

    private companion object {
        /** Generous: the first Robolectric test in a run pays for the runtime starting up. */
        const val TIMEOUT_MS = 5_000L
    }
}
