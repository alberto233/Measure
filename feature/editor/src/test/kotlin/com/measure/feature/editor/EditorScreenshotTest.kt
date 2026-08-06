package com.measure.feature.editor

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.measure.core.data.MeasureData
import com.measure.core.data.MeasureDatabase
import com.measure.core.data.MeasureRepository
import com.measure.core.geometry.CapturedCorner
import com.measure.core.geometry.Opening
import com.measure.core.geometry.OpeningKind
import com.measure.core.geometry.RoomCapture
import com.measure.core.geometry.RoomSolver
import com.measure.core.geometry.Vec2
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.Executor

/**
 * Pictures of the editor, so it can be looked at.
 *
 * These assert nothing about pixels on purpose. Roborazzi can fail a build on a diff, and
 * that is not what this is for yet: locking the current appearance in as correct would be
 * backwards when the whole point of M10a is that the current appearance is about to change.
 *
 * What they are for is that **this project's screens have never been seen**. Three
 * interface faults reached a user on hardware because they were reasoned about instead of
 * looked at — text fields the same colour as the panel behind them, a keyboard that threw
 * the editing panel off the top of the screen, and every tappable control under the
 * minimum size. All three are obvious in a picture and invisible in a diff of Kotlin.
 *
 * Once the design system lands and the appearance is meant to hold still, `verifyRoborazzi`
 * turns these into regression tests without a line of them changing.
 *
 * Written to `build/outputs/roborazzi/` and uploaded by CI.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class EditorScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var repository: MeasureRepository

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        // Inline, for the reason given at length in EditorPanelTest: Compose's clock is
        // virtual and Room's default executor is not.
        val inline = Executor { it.run() }
        val database = Room.inMemoryDatabaseBuilder(application, MeasureDatabase::class.java)
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
    }

    /** A room with a door and a window in it, so the plan has something to draw. */
    private fun seed(): Pair<Long, Long> = runBlocking {
        val projectId = repository.createProject("Plan 3")
        repository.setReference(projectId, "14 Ash Road")
        val corners = listOf(
            Vec2(0.0, 0.0),
            Vec2(4.2, 0.0),
            Vec2(4.2, 3.6),
            Vec2(0.0, 3.6),
        )
        val solution = RoomSolver.solve(RoomCapture(corners.map { CapturedCorner(it, 0.02) }))
        val roomId = repository.saveRoom(
            projectId = projectId,
            name = "Kitchen",
            solution = solution,
            measured = corners,
            sigmas = corners.map { 0.02 },
            ceilingHeight = 2.44,
            captureSession = "screenshot",
        )
        repository.addOpening(roomId, 0, Opening.standard(OpeningKind.DOOR, 4.2, 2.44))
        repository.addOpening(roomId, 1, Opening.standard(OpeningKind.WINDOW, 3.6, 2.44))
        projectId to roomId
    }

    private fun editor(projectId: Long): EditorViewModel {
        val viewModel = EditorViewModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent {
            EditorScreen(projectId = projectId, onBack = {}, onAddRoom = {}, viewModel = viewModel)
        }
        compose.waitUntil(LOAD_TIMEOUT_MS) { viewModel.current?.rooms?.isNotEmpty() == true }
        return viewModel
    }

    private fun shoot(name: String) {
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun `the plan, with nothing selected`() {
        val (projectId, _) = seed()
        editor(projectId)
        shoot("editor-plan")
    }

    @Test
    fun `a wall selected, with its openings`() {
        val (projectId, roomId) = seed()
        val viewModel = editor(projectId)
        viewModel.select(Selection.Wall(roomId, 0))
        shoot("editor-wall-selected")
    }

    /** The room panel, which is where the move and turn controls live. */
    @Test
    fun `a room selected`() {
        val (projectId, roomId) = seed()
        val viewModel = editor(projectId)
        viewModel.select(Selection.Room(roomId))
        shoot("editor-room-selected")
    }

    @Test
    fun `the measure view`() {
        val (projectId, _) = seed()
        val viewModel = editor(projectId)
        viewModel.selectMode(EditorMode.MEASURE)
        shoot("editor-measure")
    }

    /** The share sheet, which is the last thing a user sees before sending a plan on. */
    @Test
    fun `the export sheet`() {
        val (projectId, _) = seed()
        editor(projectId)
        compose.onNodeWithText("Send").performClick()
        shoot("editor-export")
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 20_000L
    }
}
