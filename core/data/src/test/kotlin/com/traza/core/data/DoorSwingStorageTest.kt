package com.traza.core.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.traza.core.geometry.CapturedCorner
import com.traza.core.geometry.DoorSwing
import com.traza.core.geometry.Opening
import com.traza.core.geometry.OpeningKind
import com.traza.core.geometry.RoomCapture
import com.traza.core.geometry.RoomSolver
import com.traza.core.geometry.Vec2
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

/**
 * How a door is hung, through the database and back.
 *
 * The risky part of this feature is storage, not drawing. The swing is kept as an enum
 * *name*, so the failure mode is silent: a renamed constant or a default that reads
 * differently on the way out does not crash, it rotates every door in every saved plan the
 * first time somebody opens the app after updating. `MIGRATION_7_8` back-fills the empty
 * string for every door that already exists, and only a round trip proves that empty string
 * still comes back as the hanging the app used to draw.
 *
 * At the repository rather than the view model, deliberately. An earlier version of this
 * drove `EditorViewModel.setDoorSwing` and waited for the change to reach the observed
 * project; it never arrived within twenty seconds of virtual time, while the same write
 * through the repository landed immediately. `resizeOpening` writes by exactly the same
 * path and has been shipping, so that is the test harness rather than the app — but it is
 * unproven either way, and a test whose failure I cannot explain is not evidence of
 * anything. This one tests the contract that is actually at risk.
 */
@RunWith(RobolectricTestRunner::class)
class DoorSwingStorageTest {

    private lateinit var repository: MeasureRepository

    private fun openRepository(): MeasureRepository {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val inline = Executor { it.run() }
        val database = Room.inMemoryDatabaseBuilder(application, MeasureDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        return MeasureRepository(database).also { repository = it }
    }

    private suspend fun seedRoom(): Pair<Long, Long> {
        val projectId = repository.createProject("Test plan")
        val corners = listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 4.0), Vec2(0.0, 4.0))
        val roomId = repository.saveRoom(
            projectId = projectId,
            name = "Kitchen",
            solution = RoomSolver.solve(RoomCapture(corners.map { CapturedCorner(it, 0.02) })),
            measured = corners,
            sigmas = corners.map { 0.02 },
            captureSession = "test-session",
        )
        return projectId to roomId
    }

    private suspend fun doorsIn(projectId: Long) =
        repository.observeProject(projectId).first()!!.rooms.single().openings[0].orEmpty()

    @Test
    fun `a door keeps the hanging it was given`() = runBlocking {
        openRepository()
        val (projectId, roomId) = seedRoom()

        val id = repository.addOpening(
            roomId,
            0,
            Opening(OpeningKind.DOOR, 1.0, 0.83, 2.04, swing = DoorSwing.HINGE_FAR_OPENS_OUT),
        )
        assertEquals(DoorSwing.HINGE_FAR_OPENS_OUT, doorsIn(projectId).single().opening.swing)

        repository.updateOpening(
            id,
            roomId,
            0,
            Opening(OpeningKind.DOOR, 1.0, 0.83, 2.04, swing = DoorSwing.HINGE_NEAR_OPENS_OUT),
        )
        assertEquals(DoorSwing.HINGE_NEAR_OPENS_OUT, doorsIn(projectId).single().opening.swing)
    }

    /**
     * A door saved without a hanging comes back hung the way the app always drew one.
     *
     * This is the shape of every pre-v8 row after the migration back-fills `''`.
     */
    @Test
    fun `an unhung door reads as the default`() = runBlocking {
        openRepository()
        val (projectId, roomId) = seedRoom()

        repository.addOpening(roomId, 0, Opening(OpeningKind.DOOR, 1.0, 0.83, 2.04))

        assertEquals(DoorSwing.HINGE_NEAR_OPENS_IN, doorsIn(projectId).single().opening.swing)
    }

    /** Both new measurement fields survive, and stay distinct from "given as nothing". */
    @Test
    fun `a plan measurement keeps its name and description`() = runBlocking {
        openRepository()
        val (projectId, _) = seedRoom()

        val id = repository.savePlanMeasurement(
            projectId,
            com.traza.core.geometry.plan.PlanAnchor.Free(Vec2(0.0, 0.0)),
            com.traza.core.geometry.plan.PlanAnchor.Free(Vec2(2.0, 0.0)),
        )
        val before = repository.observeProject(projectId).first()!!.planMeasurements.single()
        assertEquals(null, before.label)
        assertEquals(null, before.description)

        repository.describePlanMeasurement(id, "Sofa wall", "to the radiator")

        val after = repository.observeProject(projectId).first()!!.planMeasurements.single()
        assertEquals("Sofa wall", after.label)
        assertEquals("to the radiator", after.description)
    }
}
