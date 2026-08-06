package com.measure.feature.projects

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.measure.core.data.MeasureData
import com.measure.core.data.MeasureDatabase
import com.measure.core.data.MeasureRepository
import com.measure.core.data.ProjectSort
import com.measure.core.geometry.CapturedCorner
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
 * Pictures of the home screen.
 *
 * It is the first thing anyone sees and it had no coverage at all — which is exactly how
 * the units toggle labelled `"m"` reached a phone. The uppercase transform turned it into a
 * lone capital letter in a box, obvious in a picture and invisible in a diff.
 *
 * Three states, because the screen has three: nothing captured yet, a handful of plans, and
 * enough of them that search and sort appear. That last threshold is itself a design
 * decision worth being able to look at — five plans is where the controls stop being
 * furniture above a list you can already read.
 *
 * Nothing is asserted about pixels; see the note in the editor's equivalent.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ProjectsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var repository: MeasureRepository

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        // Inline, for the reason given at length in the editor's EditorPanelTest: Compose's
        // clock is virtual and Room's default executor is not.
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

    /** A plan with one room in it, so the list has a thumbnail to draw. */
    private fun seed(name: String, reference: String?, width: Double, depth: Double) = runBlocking {
        val projectId = repository.createProject(name)
        if (reference != null) repository.setReference(projectId, reference)
        val corners = listOf(
            Vec2(0.0, 0.0),
            Vec2(width, 0.0),
            Vec2(width, depth),
            Vec2(0.0, depth),
        )
        repository.saveRoom(
            projectId = projectId,
            name = "Room",
            solution = RoomSolver.solve(RoomCapture(corners.map { CapturedCorner(it, 0.02) })),
            measured = corners,
            sigmas = corners.map { 0.02 },
            ceilingHeight = 2.44,
            captureSession = "screenshot",
        )
    }

    private fun shoot(name: String, sort: ProjectSort? = null, query: String? = null) {
        val viewModel = ProjectsViewModel(ApplicationProvider.getApplicationContext())
        sort?.let(viewModel::selectSort)
        query?.let(viewModel::search)
        compose.setContent {
            ProjectsScreen(
                onNewMeasurement = {},
                onOpenProject = {},
                onDeviceCheck = {},
                viewModel = viewModel,
            )
        }
        compose.waitUntil(LOAD_TIMEOUT_MS) { viewModel.projects.value != null }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun `nothing captured yet`() {
        shoot("plans-empty")
    }

    /** Below the threshold, so search and sort stay out of the way. */
    @Test
    fun `a few plans`() {
        seed("Flat on Ash Road", "14 Ash Road", 4.2, 3.6)
        seed("Mum's kitchen", null, 3.4, 2.8)
        seed("Office", "Unit 7", 6.0, 4.5)
        shoot("plans-few")
    }

    /** Past the threshold, where finding one becomes the problem M13 was built for. */
    @Test
    fun `enough plans to need finding`() {
        seed("Flat on Ash Road", "14 Ash Road", 4.2, 3.6)
        seed("Mum's kitchen", null, 3.4, 2.8)
        seed("Office", "Unit 7", 6.0, 4.5)
        seed("Plan 4", null, 2.2, 2.0)
        seed("Studio, Calle Mayor", "Calle Mayor 3", 5.1, 4.0)
        seed("Álvaro's loft", "Loft B", 7.2, 3.1)
        shoot("plans-searchable", sort = ProjectSort.NAME)
    }
}

private const val LOAD_TIMEOUT_MS = 20_000L
