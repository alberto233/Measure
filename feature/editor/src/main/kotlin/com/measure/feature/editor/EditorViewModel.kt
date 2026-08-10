package com.measure.feature.editor

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.measure.core.data.MeasureData
import com.measure.core.designsystem.labelRes
import com.measure.core.data.ProjectDetail
import com.measure.core.data.SavedOpening
import com.measure.core.data.SavedPlanMeasurement
import com.measure.core.data.SavedRoom
import com.measure.core.geometry.CapturedCorner
import com.measure.core.geometry.Flooring
import com.measure.core.geometry.LengthConstraint
import com.measure.core.geometry.Painting
import com.measure.core.geometry.RoomQuantity
import com.measure.core.geometry.Takeoff
import com.measure.core.geometry.Opening
import com.measure.core.geometry.OpeningKind
import com.measure.core.geometry.Polygon
import com.measure.core.geometry.RoomCapture
import com.measure.core.geometry.RoomSolver
import com.measure.core.geometry.DoorSwing
import com.measure.core.geometry.Vec2
import com.measure.core.geometry.plan.DimensionChain
import com.measure.core.geometry.plan.DimensionChains
import com.measure.core.geometry.plan.PlanAnchor
import com.measure.core.geometry.plan.PlanConstraints
import com.measure.core.geometry.plan.PreferredDirection
import com.measure.core.geometry.plan.RoomPlacement
import com.measure.core.geometry.plan.SnapKind
import com.measure.core.geometry.plan.PlanMeasurement
import com.measure.core.geometry.plan.PlanSnapper
import com.measure.core.geometry.plan.ResolvedPoint
import com.measure.core.units.Area
import com.measure.core.units.AreaFormatter
import com.measure.core.units.Capacity
import com.measure.core.units.CapacityFormatter
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
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the editor has selected, which decides what the bottom panel offers. */
sealed interface Selection {
    data object None : Selection
    data class Wall(val roomId: Long, val index: Int) : Selection
    data class Corner(val roomId: Long, val index: Int) : Selection
    data class Measurement(val id: Long) : Selection
    data class Room(val roomId: Long) : Selection
}

/**
 * What the editor is for, right now.
 *
 * A mode rather than a long-press or a modifier, because a tap that sometimes selects and
 * sometimes places a point is the kind of ambiguity that produces the "I added the same
 * door seven times" class of bug.
 *
 * Three of them rather than a toggle, and stated as a segmented control rather than a
 * button that lights up. A toggle can only say "on", so a whole view had nowhere to live:
 * [QUANTITIES] answers two of the six use cases in `docs/PRODUCT_PLAN.md` §3 and could not
 * be reached at all while the mode switch was a button labelled "Measure".
 *
 * The order is the order of the work: draw the plan, read it, buy from it.
 */
enum class EditorMode {
    PLAN,
    MEASURE,
    QUANTITIES,
    ;

    @StringRes
    fun labelRes(): Int = when (this) {
        PLAN -> R.string.editor_tab_plan
        MEASURE -> R.string.editor_tab_measure
        QUANTITIES -> R.string.editor_tab_quantities
    }
}

/**
 * The one thing being read in the measure view.
 *
 * One at a time on purpose. A plan carrying every dimension it could is a drawing nobody
 * reads: the numbers collide, and the one being looked for is buried among twenty that
 * are not. Lines and ticks are always drawn, because they cost nothing to look past; a
 * number appears only when asked for.
 */
sealed interface MeasureFocus {
    data object None : MeasureFocus

    /** A run of a dimension string, or the overall span when [segment] is [OVERALL]. */
    data class Dimension(val chain: Int, val segment: Int) : MeasureFocus {
        val isOverall: Boolean get() = segment == OVERALL

        companion object {
            const val OVERALL = -1
        }
    }

    /** A distance the user drew. */
    data class Custom(val id: Long) : MeasureFocus
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

    /**
     * A string, in the phone's language.
     *
     * Every `confirm` and `warn` below is a sentence the user reads on a toast over their
     * own plan, so none of them can be a literal here. See the same helper in
     * `CaptureViewModel` — this is the second view model that needs it and the point at
     * which it stops being a one-off.
     */
    private fun say(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

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

    /**
     * The same project again, as Compose state. **Every read below goes through this.**
     *
     * `project.value` is an ordinary field read. Compose cannot see it, so a composable
     * that calls `roomById()` records no dependency on the project — and with strong
     * skipping on, a panel whose parameters have not changed is then skipped outright.
     * `WallPanel(viewModel, selection)` takes the same view model and the same selection
     * before and after a door is added, so it was skipped, and it went on rendering the
     * wall as it was before the write. The interface looked like the button had done
     * nothing; deselecting and reselecting changed `selection`, which forced the
     * recomposition that made the door appear.
     *
     * That is a whole class of fault rather than one bug — it applies to every accessor
     * on this view model — so it is fixed at the source. Reading this instead of
     * `project.value` makes each call a tracked Compose read, and any panel that renders
     * from the model recomposes when the model changes, whatever its parameters say.
     *
     * Collected for the view model's whole life rather than while subscribed: the editor
     * is on screen for as long as this exists, and a mirror that stops updating five
     * seconds after the last collector would be a subtler version of the same bug.
     */
    var current by mutableStateOf<ProjectDetail?>(null)
        private set

    init {
        viewModelScope.launch { project.collect { current = it } }
    }

    var selection by mutableStateOf<Selection>(Selection.None)
        private set

    /**
     * A transient line of feedback, and whether it is good news.
     *
     * Success used to be silent here: locking a wall, setting a ceiling height or
     * resizing an opening all did their work and said nothing, so the only way to know a
     * button had worked was to notice a number change somewhere else on screen. That is
     * the same fault as the door added seven times, in a quieter form.
     */
    var message by mutableStateOf<String?>(null)
        private set

    var messageIsWarning by mutableStateOf(true)
        private set

    private fun confirm(text: String) {
        message = text
        messageIsWarning = false
    }

    private fun warn(text: String) {
        message = text
        messageIsWarning = true
    }

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
     * A whole room being slid across the plan.
     *
     * Separate from [dragging] because it is a different act: that one reshapes a room by
     * asserting where a corner really is, this one only decides where a room sits. Sharing
     * one state would mean one code path having to remember which of the two it was in,
     * which is the shape of bug this editor keeps producing.
     */
    var movingRoom by mutableStateOf<RoomMove?>(null)
        private set

    data class RoomMove(val roomId: Long, val from: Vec2, val to: Vec2) {
        val offset: Vec2 get() = to - from
    }

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

    /** Says so when a share fails, rather than leaving a tap that appeared to do nothing. */
    fun reportExportFailure(error: Throwable) {
        warn(say(R.string.editor_cannot_share, error.javaClass.simpleName))
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

    // --- moving a whole room ------------------------------------------------------------

    /**
     * Long-press inside a room and drag to move it — docs/DEVELOPMENT.md §8.
     *
     * Needed because the app cannot know where a room measured in a separate visit goes.
     * ARCore hands out a new origin every session, so rooms captured on different trips
     * arrive in unrelated coordinate systems; they get set down in a row, and this is how
     * the user — who was there — puts them where they belong.
     *
     * Deliberately not helped along with snapping to neighbouring walls. That would make
     * the app's guess look like a measurement again, in a new place, and the whole reason
     * this control exists is that a placement should be traceable to whoever made it.
     */
    fun beginRoomMove(roomId: Long, at: Vec2) {
        movingRoom = RoomMove(roomId, at, at)
        selection = Selection.Room(roomId)
    }

    fun updateRoomMove(at: Vec2) {
        movingRoom = movingRoom?.copy(to = at)
    }

    fun endRoomMove() {
        val move = movingRoom ?: return
        movingRoom = null

        val offset = move.offset
        if (offset.length < MINIMUM_MOVE_METRES) return

        val room = roomById(move.roomId) ?: return
        remember(room)

        val projectId = projectId
        viewModelScope.launch {
            repository.moveRoom(move.roomId, projectId, offset.x, offset.y)
            confirm(say(R.string.editor_room_moved, room.name))
        }
    }

    fun cancelRoomMove() {
        movingRoom = null
    }

    /**
     * Turns a room by a fixed step, for picking which way round it goes.
     *
     * Buttons rather than a two-finger twist. The plan already pinches to zoom, and a
     * gesture that sometimes scales the view and sometimes rotates a room is the same
     * ambiguity as a tap that sometimes selects and sometimes places a point — which is
     * the fault this editor has produced more than any other.
     */
    fun turnRoom(roomId: Long, degrees: Double) {
        val room = roomById(roomId) ?: return
        remember(room)
        val projectId = projectId
        viewModelScope.launch {
            repository.rotateRoom(roomId, projectId, Math.toRadians(degrees))
            confirm(say(R.string.editor_room_turned, room.name, degrees.roundToInt()))
        }
    }

    /**
     * Lines a room's walls up with the grid the rest of the plan is built on.
     *
     * One tap for the part of arranging that is mechanical. What is left after it is which
     * of the four quarter turns is wanted, which is a question about where the door and the
     * window are and only the user can answer — hence the two turn buttons beside it.
     */
    fun squareRoomToPlan(roomId: Long) {
        val room = roomById(roomId) ?: return
        val others = current?.rooms.orEmpty().filter { it.id != roomId }

        // The grid of everything else, or the world axes when this is the only room and
        // there is no plan for it to agree with yet.
        val reference = if (others.isEmpty()) {
            Vec2(1.0, 0.0)
        } else {
            DimensionChains.dominantDirection(others.map { it.outline })
        }

        val angle = RoomPlacement.squaringAngle(room.outline, reference)
        if (abs(angle) < MINIMUM_TURN_RADIANS) {
            confirm(say(R.string.editor_already_square, room.name))
            return
        }

        remember(room)
        val projectId = projectId
        viewModelScope.launch {
            repository.rotateRoom(roomId, projectId, angle)
            confirm(
                say(R.string.editor_room_squared, room.name, Math.toDegrees(angle).roundToInt()),
            )
        }
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
            warn(say(R.string.editor_cannot_read, typed, say(R.string.editor_as_length)))
            return
        }

        remember(room)
        viewModelScope.launch {
            repository.setLockedLength(roomId, index, parsed.metres)
            resolveWith(room, room.measured, room.sigmas, room.lockedLengths + (index to parsed.metres))
            confirm(say(R.string.editor_wall_locked_to, index + 1, formatLength(parsed.metres)))
        }
    }

    fun unlockWall(roomId: Long, index: Int) {
        val room = roomById(roomId) ?: return
        remember(room)
        viewModelScope.launch {
            repository.setLockedLength(roomId, index, null)
            resolveWith(room, room.measured, room.sigmas, room.lockedLengths - index)
            confirm(say(R.string.editor_wall_unlocked, index + 1))
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

    fun measurementById(id: Long) = current?.measurements?.firstOrNull { it.id == id }

    // --- measuring on the plan — docs/PRODUCT_PLAN.md M12 -------------------------------

    var mode by mutableStateOf(EditorMode.PLAN)
        private set

    /**
     * What is being read right now — a set, because reading is a *comparison* as often as
     * it is a lookup.
     *
     * "How wide is that wall" is one number. "Do these three runs add up to the wall
     * opposite" is the question a single selection cannot answer, and it is the one people
     * are actually asking when they tap a second dimension. Tapping toggles, so a
     * selection is built up and taken apart rather than replaced.
     */
    var focuses by mutableStateOf<Set<MeasureFocus>>(emptySet())
        private set

    /**
     * The single thing being read, when there is exactly one.
     *
     * Kept so the detailed readouts and the plan's guide line stay single-subject. A guide
     * projected across the plan for five selected runs at once is five lines nobody can
     * follow, and a readout describing "the marked corners" is only true of one of them.
     */
    val focus: MeasureFocus get() = focuses.singleOrNull() ?: MeasureFocus.None

    /** True while points are being placed for a new distance. */
    var drawing by mutableStateOf(false)
        private set

    /**
     * The first end of a measurement being drawn, waiting for its partner.
     *
     * Held resolved rather than as a raw tap so the interface can say what it latched
     * onto the instant it is placed. On a touch screen there is no hover, so the only
     * moment to tell the user "that went on corner 2 of Room 1" is straight after the tap
     * — and if it went somewhere they did not mean, they need to know before the second
     * tap commits a measurement built on it.
     */
    var pendingEnd by mutableStateOf<ResolvedPoint?>(null)
        private set

    /**
     * A measurement just drawn and not yet accepted.
     *
     * Nothing else can be started while one is sitting here. Drawing a second distance
     * the moment the first lands is how a plan quietly fills up with lines nobody meant
     * to keep — and it is the same fault as the door added seven times, which was also a
     * control that acted without ever asking whether the last one was wanted.
     */
    var unconfirmed by mutableStateOf<Long?>(null)
        private set

    /** What the last placed end was straightened onto, for the banner to report. */
    var lastStraightening by mutableStateOf<String?>(null)
        private set

    /**
     * Named `selectMode` rather than `setMode`, which would clash with the property's own
     * generated setter on the JVM. `CaptureViewModel.selectMode` has the same name for the
     * same reason.
     */
    fun selectMode(next: EditorMode) {
        if (next == mode) return
        mode = next
        resetMeasuring()
        selection = Selection.None
    }

    private fun resetMeasuring() {
        focuses = emptySet()
        drawing = false
        pendingEnd = null
        unconfirmed = null
        lastStraightening = null
    }

    fun focusOn(next: MeasureFocus) {
        if (drawing) return
        // A measurement waiting to be kept holds the view until it is answered. Tapping
        // the plan is the natural way to dismiss a card, and dismissing this one would
        // leave a saved distance the user never agreed to and can no longer see. Said
        // rather than silently ignored, so the tap is not simply dead.
        if (unconfirmed != null) {
            warn(say(R.string.editor_finish_this))
            return
        }
        // Toggle rather than replace. Tapping a selected run again is how a comparison is
        // narrowed, and it is also the only way back to nothing without hunting for a
        // clear button.
        focuses = if (next in focuses) focuses - next else focuses + next
    }

    fun clearFocus() {
        focuses = emptySet()
    }

    /**
     * Every selected length, in the order the plan reads them.
     *
     * Only lengths. A dimension run and a custom distance are both distances and add up to
     * something meaningful; nothing else in the measure view does, which is why rooms and
     * areas are not selectable here.
     */
    fun selectedLengths(): List<Double> {
        val chains = dimensionChains()
        return focuses.mapNotNull { target ->
            when (target) {
                is MeasureFocus.Dimension -> chains.getOrNull(target.chain)?.let { chain ->
                    if (target.isOverall) chain.overall else chain.segments.getOrNull(target.segment)?.length
                }

                is MeasureFocus.Custom -> planMeasurementById(target.id)?.measurement?.length
                MeasureFocus.None -> null
            }
        }
    }

    /** Begin placing points. Refused until the last measurement has been accepted. */
    fun beginDrawing() {
        if (unconfirmed != null) {
            warn(say(R.string.editor_finish_last))
            return
        }
        drawing = true
        pendingEnd = null
        lastStraightening = null
        focuses = emptySet()
    }

    fun cancelDrawing() {
        drawing = false
        pendingEnd = null
        lastStraightening = null
    }

    /** Drops the half-placed end without leaving the tool, for a mis-tap. */
    fun clearPendingEnd() {
        pendingEnd = null
        lastStraightening = null
    }

    /**
     * Keeps the measurement, optionally with a name and a description.
     *
     * Both are optional and both are trimmed to null when blank: a measurement someone
     * skipped past is not one named with a space. The distinction matters downstream —
     * `label` being null is what stops the plan drawing an empty caption under a number.
     */
    fun keepMeasurement(name: String? = null, description: String? = null) {
        val id = unconfirmed ?: return
        unconfirmed = null
        val cleanName = name?.trim()?.ifBlank { null }
        val cleanDescription = description?.trim()?.ifBlank { null }
        if (cleanName == null && cleanDescription == null) return
        viewModelScope.launch {
            repository.describePlanMeasurement(id, cleanName, cleanDescription)
        }
    }

    fun discardMeasurement() {
        val id = unconfirmed ?: return
        unconfirmed = null
        focuses = emptySet()
        viewModelScope.launch { repository.deletePlanMeasurement(id) }
    }

    /** True when a tap on the plan will be refused because something needs answering. */
    val isHoldingConfirmation: Boolean get() = unconfirmed != null

    /**
     * A tap while drawing: the first places an end, the second completes and saves.
     *
     * Saved immediately, like everything else here, then held for confirmation so the
     * user reads the number they just drew before anything else can be drawn.
     */
    fun tapWhileMeasuring(point: Vec2, reach: Double) {
        if (!drawing) return
        val rooms = current?.snapRooms.orEmpty()

        val first = pendingEnd
        if (first == null) {
            pendingEnd = PlanSnapper.snap(rooms, point, reach)
            return
        }

        // Snap first, straighten second. A tap that landed on a corner or a wall was
        // aimed at that thing and should stay on it; only a free point — the "somewhere
        // over here" end, which is where the finger error actually lives — gets pulled
        // square. Straightening a corner off its corner would be the tool overruling an
        // instruction rather than removing noise.
        val snapped = PlanSnapper.snap(rooms, point, reach)
        val placed = when (snapped.kind) {
            SnapKind.FREE -> straighten(first, snapped)
            // Landed on a wall: the wall was aimed at and hit, so it keeps its anchor and
            // only slides along it until the crossing is square.
            SnapKind.WALL -> squareAcross(first, snapped) ?: snapped
            SnapKind.CORNER -> snapped
        }

        val candidate = PlanMeasurement(first, placed)
        if (candidate.isDegenerate) {
            // Refused rather than stored: a zero-length measurement is a double tap, and
            // silently saving one leaves the user hunting for a line that is a dot.
            warn(say(R.string.editor_same_point))
            return
        }

        pendingEnd = null
        drawing = false
        val projectId = projectId
        viewModelScope.launch {
            val id = repository.savePlanMeasurement(projectId, first.anchor, placed.anchor)
            unconfirmed = id
            // Replaces the selection rather than joining it: a brand new measurement
            // awaiting confirmation is the only thing the panel can usefully be about.
            focuses = setOf(MeasureFocus.Custom(id))
        }
    }

    /**
     * Pulls a free end square to the wall the measurement started from.
     *
     * "How far is the bed from the wall" means the perpendicular distance, and a line a
     * few degrees off perpendicular is not a worse drawing of it — it is a measurement of
     * something else, and it always reads long. The wall's direction is known exactly, so
     * the error a finger contributes along the wall can simply be removed.
     *
     * The plan's own grid is offered as a fallback, so a measurement between two free
     * points still comes out straight rather than very slightly crooked.
     */
    private fun straighten(from: ResolvedPoint, to: ResolvedPoint): ResolvedPoint {
        val preferred = buildList {
            wallDirectionOf(from)?.let {
                add(
                    PreferredDirection(
                        it.perpendicular(),
                        say(R.string.editor_square_to, from.description),
                    ),
                )
                add(PreferredDirection(it, say(R.string.editor_along, from.description)))
            }
            val grid = dominantDirection()
            add(
                PreferredDirection(
                    grid.perpendicular(),
                    say(R.string.editor_square_to_the_plan),
                ),
            )
            add(PreferredDirection(grid, say(R.string.editor_along_plan)))
        }

        val result = PlanConstraints.straighten(from.position, to.position, preferred)
        if (result.description == null) return to

        // Said out loud when it moved the point a long way, because that means the user
        // was pointing at something this constraint does not describe.
        if (result.isNotable) {
            warn(
                say(
                    R.string.editor_moved_to_keep,
                    formatLength(result.correction),
                    result.description.orEmpty(),
                ),
            )
        }
        lastStraightening = result.description
        return to.copy(anchor = PlanAnchor.Free(result.position), position = result.position)
    }

    /**
     * Squares a wall-to-wall measurement by sliding the far end along the wall it hit.
     *
     * This is the measurement people most want to be exact — how wide is the room, how
     * far apart are these two walls — and it was the one case the general straightener
     * deliberately skipped, because the far end had landed on real geometry. It had, and
     * it should stay there; what needed fixing was only how far along that wall the line
     * arrived. Sliding rather than projecting keeps the anchor, so the measurement still
     * follows the wall when the room is re-solved.
     */
    private fun squareAcross(from: ResolvedPoint, to: ResolvedPoint): ResolvedPoint? {
        val anchor = to.anchor as? PlanAnchor.Wall ?: return null
        val room = current?.rooms?.firstOrNull { it.id == anchor.roomId } ?: return null
        val outline = room.outline
        if (outline.size < 3 || anchor.index !in outline.indices) return null

        // Square to the wall the measurement started from, or to the plan's grid when it
        // started in open space.
        val reference = wallDirectionOf(from) ?: dominantDirection()

        val t = PlanConstraints.squareAlongWall(
            from = from.position,
            reference = reference,
            wallStart = outline[anchor.index],
            wallEnd = outline[(anchor.index + 1) % outline.size],
            currentPosition = to.position,
        ) ?: return null

        val squared = PlanSnapper.resolve(
            current?.snapRooms.orEmpty(),
            anchor.copy(t = t),
        ) ?: return null

        lastStraightening = say(R.string.editor_square_to, from.description)
        return squared
    }

    private fun wallDirectionOf(point: ResolvedPoint): Vec2? {
        val anchor = point.anchor as? PlanAnchor.Wall ?: return null
        val room = current?.rooms?.firstOrNull { it.id == anchor.roomId } ?: return null
        val outline = room.outline
        if (outline.size < 3 || anchor.index !in outline.indices) return null
        val from = outline[anchor.index]
        val to = outline[(anchor.index + 1) % outline.size]
        return (to - from).takeIf { it.length > Vec2.EPSILON }?.normalised()
    }

    private fun dominantDirection(): Vec2 =
        DimensionChains.dominantDirection(current?.rooms.orEmpty().map { it.outline })

    /**
     * The dimension strings, **one set per room** — how wide, how deep, where it steps.
     *
     * Computed rather than stored, and shown only while measuring: this is what most
     * people opened the plan to find out, and making them tap two points accurately for
     * it was asking for a steady finger to answer a question the geometry already knows.
     *
     * Per room rather than per plan, which is not how an architect's drawing does it. Two
     * reasons, and the second is the one that settles it:
     *
     * 1. A plan-wide chain puts every room's corners on one line of ticks, so a flat of
     *    four rooms produces a string of a dozen runs and the number wanted is buried
     *    among eleven that are not. That notation assumes a reader who is used to it.
     * 2. A chain spanning two rooms **measures between them** — and until captures are
     *    registered against each other, that distance is a layout somebody arranged, not
     *    something anybody measured. Drawing it in the most authoritative notation on the
     *    page would undo exactly what recording the capture frame was for.
     *
     * The plan-wide overall can come back when it is earned, which means when two rooms
     * share a wall the app knows about rather than one it drew them next to.
     */
    fun dimensionChains(): List<DimensionChain> =
        current?.rooms.orEmpty().flatMap { DimensionChains.chains(listOf(it.outline)) }

    /** The length the focused dimension is reporting, if one is focused. */
    fun focusedDimensionLength(): Double? {
        val target = focus as? MeasureFocus.Dimension ?: return null
        val chain = dimensionChains().getOrNull(target.chain) ?: return null
        return if (target.isOverall) chain.overall else chain.segments.getOrNull(target.segment)?.length
    }

    fun planMeasurementById(id: Long): SavedPlanMeasurement? =
        current?.planMeasurements?.firstOrNull { it.id == id }

    fun deletePlanMeasurement(id: Long) {
        viewModelScope.launch { repository.deletePlanMeasurement(id) }
        // Clearing the *selection* was not enough: the measure view reads `focus`, so the
        // card went on showing a measurement that no longer existed.
        focuses = focuses - MeasureFocus.Custom(id)
        if (unconfirmed == id) unconfirmed = null
        selection = Selection.None
    }

    /** The bounding box of a room, which is the number people check a sofa against. */
    fun boundingSize(room: SavedRoom): Pair<Double, Double>? {
        if (room.outline.size < 3) return null
        val xs = room.outline.map { it.x }
        val ys = room.outline.map { it.y }
        return (xs.max() - xs.min()) to (ys.max() - ys.min())
    }

    // --- quantities — docs/PRODUCT_PLAN.md §3, use cases 3 and 4 ------------------------

    /**
     * How much of the floor gets cut off and thrown away, as a percentage.
     *
     * Held here rather than stored with the project. It is a fact about how a floor is being
     * laid rather than about the room, it is changed while looking at the answer, and a
     * persisted copy would be one more thing to migrate for no gain. If it turns out people
     * set it once and expect it to stick, it becomes a preference in M10c.
     */
    var wastePercent by mutableStateOf(Flooring.DEFAULT_WASTE)
        private set

    var coats by mutableStateOf(Painting.DEFAULT_COATS)
        private set

    /** Ceilings are painted about as often as they are not, so this is a choice, not a rule. */
    var paintCeilings by mutableStateOf(false)
        private set

    /** Named like [selectMode] and for the same reason: `setWaste` would clash on the JVM. */
    fun selectWaste(percent: Int) {
        wastePercent = percent
    }

    fun selectCoats(count: Int) {
        coats = count
    }

    fun togglePaintCeilings() {
        paintCeilings = !paintCeilings
    }

    /**
     * The whole plan added up, room by room.
     *
     * Computed on every read rather than cached. It is a sum over a handful of rooms, and a
     * cache would be a second copy of the model that could disagree with the first — which
     * is the fault this view model was rewritten to remove.
     */
    fun takeoff(): Takeoff = Takeoff(
        current?.rooms.orEmpty().map { room ->
            RoomQuantity(
                name = room.name,
                floorArea = room.area,
                perimeter = room.perimeter,
                surfaces = room.surfaces,
            )
        },
    )

    /** Wall area, plus the ceilings when they are being painted too. */
    fun paintableArea(takeoff: Takeoff): Area =
        if (paintCeilings) takeoff.netWallArea + takeoff.ceilingArea else takeoff.netWallArea

    fun flooringRequired(takeoff: Takeoff): Area = Flooring.required(takeoff.floorArea, wastePercent)

    fun paintRequired(takeoff: Takeoff): Capacity = Painting.required(paintableArea(takeoff), coats)

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
            warn(say(R.string.editor_opening_no_room))
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
            lastAddedOpening = repository.addOpening(roomId, wallIndex, placed)
            confirm(say(R.string.editor_opening_added, say(kind.labelRes()), wallIndex + 1))
        }
    }

    /**
     * The opening added most recently, so the panel can scroll its row into view.
     *
     * Held rather than signalled once, because the panel reads it from a `LaunchedEffect`
     * keyed on it and a value that cleared itself would race that read.
     */
    var lastAddedOpening by mutableStateOf<Long?>(null)
        private set

    /**
     * Re-hangs a door: which end the hinge is on, and which way it opens.
     *
     * Separate from [resizeOpening] because it changes nothing about where the opening is
     * or how big it is, and routing it through the resize path would put it through
     * validation that has nothing to say about a hinge.
     */
    fun setDoorSwing(roomId: Long, saved: SavedOpening, swing: DoorSwing) {
        val room = roomById(roomId) ?: return
        viewModelScope.launch {
            repository.updateOpening(saved.id, room.id, saved.wallIndex, saved.opening.copy(swing = swing))
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
            warn(say(R.string.editor_opening_too_big))
            return
        }
        viewModelScope.launch {
            repository.updateOpening(saved.id, roomId, saved.wallIndex, updated)
            confirm(say(R.string.editor_opening_updated, say(updated.kind.labelRes())))
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
            viewModelScope.launch {
                repository.setCeilingHeight(roomId, null)
                confirm(say(R.string.editor_ceiling_cleared))
            }
            return
        }
        val parsed = LengthParser.parse(typed, unitSystem())
        if (parsed == null || parsed.metres <= 0.0) {
            warn(say(R.string.editor_cannot_read, typed, say(R.string.editor_as_height)))
            return
        }
        viewModelScope.launch {
            repository.setCeilingHeight(roomId, parsed.metres)
            confirm(say(R.string.editor_ceiling_set, formatLength(parsed.metres)))
        }
    }

    fun wallLength(room: SavedRoom, wallIndex: Int): Double? {
        val outline = room.outline
        if (outline.size < 3 || wallIndex !in outline.indices) return null
        return outline[wallIndex].distanceTo(outline[(wallIndex + 1) % outline.size])
    }

    fun renameRoom(roomId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.renameRoom(roomId, trimmed)
            confirm(say(R.string.editor_renamed_to, trimmed))
        }
    }

    fun deleteRoom(roomId: Long) {
        val name = roomById(roomId)?.name
        viewModelScope.launch {
            repository.deleteRoom(roomId)
            confirm(
                say(
                    R.string.editor_opening_deleted,
                    name ?: say(R.string.editor_default_room_name),
                ),
            )
        }
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
            warn(say(R.string.editor_cannot_resolve))
            return
        }

        if (!solution.solver.converged) {
            warn(say(R.string.editor_constraints_conflict))
        }
        repository.updateRoomGeometry(room.id, solution, corners, sigmas)
    }

    // --- formatting -----------------------------------------------------------------

    fun unitSystem(): UnitSystem = current?.unitSystem ?: UnitSystem.METRIC

    /** Parses a typed length in the project's units, or null if it is not one. */
    fun parseLength(text: String): Double? =
        LengthParser.parse(text, unitSystem())?.metres?.takeIf { it > 0.0 }

    fun formatLength(metres: Double): String =
        LengthFormatter.format(Length(metres), unitSystem())

    fun formatArea(room: SavedRoom): String = AreaFormatter.format(room.area, unitSystem())

    fun formatArea(area: Area): String = AreaFormatter.format(area, unitSystem())

    /** The number on its own, for a reading that sets the unit separately. */
    fun areaValue(area: Area): String = AreaFormatter.value(area, unitSystem())

    fun areaUnit(): String = AreaFormatter.unit(unitSystem())

    fun capacityValue(capacity: Capacity): String = CapacityFormatter.value(capacity, unitSystem())

    fun capacityUnit(): String = CapacityFormatter.unit(unitSystem())

    fun roomById(roomId: Long): SavedRoom? = current?.rooms?.firstOrNull { it.id == roomId }

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

        /**
         * Below this, a room move was a long-press that wobbled. Writing it anyway would
         * cost an undo slot and a confirmation for a change nobody can see.
         */
        const val MINIMUM_MOVE_METRES = 0.01

        /** Below a tenth of a degree a room is already square; saying so beats a no-op. */
        val MINIMUM_TURN_RADIANS = Math.toRadians(0.1)
    }
}
