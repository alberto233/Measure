package com.measure.feature.editor

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.measure.core.data.MeasureData
import com.measure.core.data.ProjectDetail
import com.measure.core.data.SavedOpening
import com.measure.core.data.SavedRoom
import com.measure.core.geometry.CapturedCorner
import com.measure.core.geometry.LengthConstraint
import com.measure.core.geometry.Opening
import com.measure.core.geometry.OpeningKind
import com.measure.core.geometry.Polygon
import com.measure.core.geometry.RoomCapture
import com.measure.core.geometry.RoomSolver
import com.measure.core.geometry.Vec2
import com.measure.core.units.AreaFormatter
import com.measure.core.units.Length
import com.measure.core.units.LengthFormatter
import com.measure.core.units.LengthParser
import com.measure.core.units.UnitSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the editor has selected, which decides what the bottom panel offers. */
sealed interface Selection {
    data object None : Selection
    data class Wall(val roomId: Long, val index: Int) : Selection
    data class Corner(val roomId: Long, val index: Int) : Selection
    data class Measurement(val id: Long) : Selection
    data class Room(val roomId: Long) : Selection
}

/**
 * Everything needed to put one room back exactly as it was.
 *
 * The observations rather than the solved outline, because those are what a solve starts
 * from — restoring a solution would reinstate the shape but not the thing that produced
 * it, and the next edit would then diverge from the room the user thought they had.
 */
private data class RoomSnapshot(
    val roomId: Long,
    val measured: List<Vec2>,
    val sigmas: List<Double>,
    val lockedLengths: Map<Int, Double>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MeasureData.repository(application)

    private val projectIdFlow = MutableStateFlow(0L)

    val projectId: Long get() = projectIdFlow.value

    /**
     * Straight from the database rather than held as editable copies.
     *
     * Every edit writes and the screen redraws from what came back, so what is on screen
     * is always what is stored. The alternative — an in-memory model synced to the
     * database — is where "the plan looked right but saved wrong" bugs live.
     */
    val project: StateFlow<ProjectDetail?> = projectIdFlow
        .flatMapLatest { id -> if (id == 0L) flowOf(null) else repository.observeProject(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    var selection by mutableStateOf<Selection>(Selection.None)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    /**
     * Corners being dragged live, before the solve runs.
     *
     * The solve is deferred to the end of a drag rather than run per frame. Not for
     * performance — the problem is tiny — but because a solve that fires continuously
     * fights the finger: right angles snap in and shove the corner away from where it is
     * being held, which feels like the plan resisting the user.
     */
    var dragging by mutableStateOf<DragState?>(null)
        private set

    data class DragState(val roomId: Long, val index: Int, val position: Vec2)

    /**
     * One step of undo per edit.
     *
     * A dragged corner is a freehand judgement, and the usual outcome of a freehand
     * judgement is wanting the previous one back. Bounded because this is a safety net
     * for the last few actions, not a document history.
     */
    private val undoStack = ArrayDeque<RoomSnapshot>()

    var canUndo by mutableStateOf(false)
        private set

    fun load(projectId: Long) {
        projectIdFlow.value = projectId
    }

    // --- selection ------------------------------------------------------------------

    fun select(selection: Selection) {
        this.selection = selection
    }

    fun clearSelection() {
        selection = Selection.None
    }

    fun dismissMessage() {
        message = null
    }

    // --- dragging -------------------------------------------------------------------

    fun beginDrag(roomId: Long, index: Int, position: Vec2) {
        dragging = DragState(roomId, index, position)
        selection = Selection.Corner(roomId, index)
    }

    fun updateDrag(position: Vec2) {
        dragging = dragging?.copy(position = position)
    }

    /**
     * Commits a drag and re-solves.
     *
     * The dragged corner is fed back in as a *measurement* with a tight sigma rather than
     * being pinned outright. The user is asserting where the corner is, and should be
     * believed — but the walls still have to stay rectilinear and any locked length still
     * has to hold, and letting the solver reconcile all three is the whole point of
     * having one. Pinning would make a drag able to break constraints the user set.
     */
    fun endDrag() {
        val drag = dragging ?: return
        dragging = null

        val room = roomById(drag.roomId) ?: return
        remember(room)
        // The drag replaces the *observation*, not the solved position. The user is
        // telling us where the corner really is, which supersedes what the camera saw.
        val moved = room.measured.toMutableList()
        if (drag.index !in moved.indices) return
        moved[drag.index] = drag.position

        val sigmas = room.sigmas.toMutableList()
        while (sigmas.size < moved.size) sigmas += DEFAULT_SIGMA
        sigmas[drag.index] = DRAGGED_CORNER_SIGMA

        resolve(room, moved, sigmas)
    }

    // --- locked lengths -------------------------------------------------------------

    /**
     * Locks a wall to a hand-measured length and re-solves — docs/ACCURACY.md M8.
     *
     * This is the most valuable thing the editor does. One wall measured with a tape and
     * typed in is a piece of certainty in a plan otherwise made of camera estimates, and
     * because the solve moves every corner at once against every constraint at once, that
     * one true number pulls the whole room towards being right rather than just fixing
     * the wall it was entered for.
     */
    fun lockWall(roomId: Long, index: Int, typed: String) {
        val room = roomById(roomId) ?: return
        val parsed = LengthParser.parse(typed, unitSystem())
        if (parsed == null || parsed.metres <= 0.0) {
            message = "Could not read \"$typed\" as a length"
            return
        }

        remember(room)
        viewModelScope.launch {
            repository.setLockedLength(roomId, index, parsed.metres)
            resolveWith(room, room.measured, room.sigmas, room.lockedLengths + (index to parsed.metres))
        }
    }

    fun unlockWall(roomId: Long, index: Int) {
        val room = roomById(roomId) ?: return
        remember(room)
        viewModelScope.launch {
            repository.setLockedLength(roomId, index, null)
            resolveWith(room, room.measured, room.sigmas, room.lockedLengths - index)
        }
    }

    // --- undo ------------------------------------------------------------------------

    private fun remember(room: SavedRoom) {
        undoStack.addLast(
            RoomSnapshot(room.id, room.measured, room.sigmas, room.lockedLengths),
        )
        while (undoStack.size > UNDO_DEPTH) undoStack.removeFirst()
        canUndo = true
    }

    /** Puts the last edited room back as it was, locks included. */
    fun undo() {
        val snapshot = undoStack.removeLastOrNull() ?: return
        canUndo = undoStack.isNotEmpty()

        val room = roomById(snapshot.roomId) ?: return
        viewModelScope.launch {
            // Locks are rows of their own, so restoring geometry is not enough — a lock
            // added by the edit being undone would otherwise survive it and immediately
            // pull the restored room back out of shape.
            val currentIndices = room.lockedLengths.keys + snapshot.lockedLengths.keys
            currentIndices.forEach { index ->
                repository.setLockedLength(snapshot.roomId, index, snapshot.lockedLengths[index])
            }
            resolveWith(room, snapshot.measured, snapshot.sigmas, snapshot.lockedLengths)
        }
        selection = Selection.None
    }

    fun deleteMeasurement(id: Long) {
        viewModelScope.launch { repository.deleteMeasurement(id) }
        selection = Selection.None
    }

    fun measurementById(id: Long) = project.value?.measurements?.firstOrNull { it.id == id }

    // --- openings and heights ---------------------------------------------------------

    fun addOpening(roomId: Long, wallIndex: Int, kind: OpeningKind) {
        val room = roomById(roomId) ?: return
        val wallLength = wallLength(room, wallIndex) ?: return
        val height = room.ceilingHeight ?: DEFAULT_CEILING_HEIGHT

        // Refuse once the wall is full rather than stacking openings on top of one
        // another. Every new one is centred, so a second identical door lands exactly on
        // the first, and from the plan the two are indistinguishable from one.
        val existing = room.openings[wallIndex].orEmpty()
        val used = existing.sumOf { it.opening.width }
        val candidate = Opening.standard(kind, wallLength, height)
        if (used + candidate.width > wallLength) {
            message = "No room left in that wall"
            return
        }

        viewModelScope.launch {
            // Placed after what is already there rather than centred, so a second opening
            // is visibly a second one.
            val placed = if (existing.isEmpty()) {
                candidate
            } else {
                candidate.copy(offset = (used + GAP_BETWEEN_OPENINGS).coerceAtMost(wallLength - candidate.width))
            }
            repository.addOpening(roomId, wallIndex, placed)
            message = "${kind.label} added"
        }
    }

    fun resizeOpening(
        roomId: Long,
        saved: SavedOpening,
        width: Double? = null,
        height: Double? = null,
        offset: Double? = null,
        sill: Double? = null,
    ) {
        val room = roomById(roomId) ?: return
        val wallLength = wallLength(room, saved.wallIndex) ?: return
        val ceiling = room.ceilingHeight ?: DEFAULT_CEILING_HEIGHT

        val updated = saved.opening.copy(
            width = width ?: saved.opening.width,
            height = height ?: saved.opening.height,
            offset = offset ?: saved.opening.offset,
            sillHeight = sill ?: saved.opening.sillHeight,
        )
        if (!updated.fitsIn(wallLength, ceiling)) {
            message = "That will not fit this wall"
            return
        }
        viewModelScope.launch {
            repository.updateOpening(saved.id, roomId, saved.wallIndex, updated)
        }
    }

    fun deleteOpening(id: Long) {
        viewModelScope.launch { repository.deleteOpening(id) }
    }

    /**
     * Sets the room height by hand.
     *
     * Needed even with ceiling detection, because a ceiling only gets detected if the
     * user happened to look up at one that ARCore could fit — which rules out sloped
     * ceilings, dark rooms and anywhere with something large overhead.
     */
    fun setCeilingHeight(roomId: Long, typed: String) {
        if (typed.isBlank()) {
            viewModelScope.launch { repository.setCeilingHeight(roomId, null) }
            return
        }
        val parsed = LengthParser.parse(typed, unitSystem())
        if (parsed == null || parsed.metres <= 0.0) {
            message = "Could not read \"$typed\" as a height"
            return
        }
        viewModelScope.launch { repository.setCeilingHeight(roomId, parsed.metres) }
    }

    fun wallLength(room: SavedRoom, wallIndex: Int): Double? {
        val outline = room.outline
        if (outline.size < 3 || wallIndex !in outline.indices) return null
        return outline[wallIndex].distanceTo(outline[(wallIndex + 1) % outline.size])
    }

    fun renameRoom(roomId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.renameRoom(roomId, trimmed) }
    }

    fun deleteRoom(roomId: Long) {
        viewModelScope.launch { repository.deleteRoom(roomId) }
        selection = Selection.None
    }

    // --- solving --------------------------------------------------------------------

    private fun resolve(room: SavedRoom, corners: List<Vec2>, sigmas: List<Double>) {
        viewModelScope.launch { resolveWith(room, corners, sigmas, room.lockedLengths) }
    }

    /**
     * Re-solves from the observations, never from the previous solution.
     *
     * Solving a solution again is not a no-op: the direction constraints never fully win
     * against the position residuals, so each pass shifts the corners a few millimetres
     * further towards perfect right angles. Anchoring every solve to the same
     * measurements makes editing idempotent — locking a wall and unlocking it again
     * returns exactly the room you started with.
     */
    private suspend fun resolveWith(
        room: SavedRoom,
        corners: List<Vec2>,
        sigmas: List<Double>,
        locked: Map<Int, Double>,
    ) {
        if (corners.size < 3) return

        val constraints = locked.mapNotNull { (index, length) ->
            if (index !in corners.indices) {
                null
            } else {
                LengthConstraint(
                    fromIndex = index,
                    toIndex = (index + 1) % corners.size,
                    length = length,
                )
            }
        }

        val solution = runCatching {
            RoomSolver.solve(
                // No closing observation: the drift was already distributed when the room
                // was captured, and re-applying it to the adjusted corners would count
                // the same error twice.
                RoomCapture(corners.mapIndexed { i, c -> CapturedCorner(c, sigmas.getOrElse(i) { DEFAULT_SIGMA }) }),
                lockedLengths = constraints,
            )
        }.getOrElse {
            message = "Could not re-solve this room"
            return
        }

        if (!solution.solver.converged) {
            message = "Constraints conflict — try unlocking a wall"
        }
        repository.updateRoomGeometry(room.id, solution, corners, sigmas)
    }

    // --- formatting -----------------------------------------------------------------

    fun unitSystem(): UnitSystem = project.value?.unitSystem ?: UnitSystem.METRIC

    /** Parses a typed length in the project's units, or null if it is not one. */
    fun parseLength(text: String): Double? =
        LengthParser.parse(text, unitSystem())?.metres?.takeIf { it > 0.0 }

    fun formatLength(metres: Double): String =
        LengthFormatter.format(Length(metres), unitSystem())

    fun formatArea(room: SavedRoom): String = AreaFormatter.format(room.area, unitSystem())

    fun roomById(roomId: Long): SavedRoom? = project.value?.rooms?.firstOrNull { it.id == roomId }

    fun polygonOf(room: SavedRoom): Polygon? =
        if (room.outline.size >= 3) Polygon(room.outline) else null

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val UNDO_DEPTH = 20

        /**
         * Used only to size a *new* opening sensibly when no height is known yet. Never
         * shown as a measurement and never stored — a guessed height presented as a
         * measured one is the dishonesty this app is built to avoid.
         */
        const val DEFAULT_CEILING_HEIGHT = 2.4

        /** A little clear wall between one opening and the next. */
        const val GAP_BETWEEN_OPENINGS = 0.1
        const val DEFAULT_SIGMA = 0.02

        /**
         * How much a dragged corner is trusted, in metres. Tight enough that the corner
         * goes essentially where it was put, loose enough that a locked wall length still
         * wins the argument.
         */
        const val DRAGGED_CORNER_SIGMA = 0.003
    }
}
