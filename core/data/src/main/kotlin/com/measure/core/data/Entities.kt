package com.measure.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The stored schema — docs/TECHNICAL_DESIGN.md §3.
 *
 * Two rules hold throughout and are worth stating because breaking either is silent:
 *
 * 1. **Everything is metres, as `Double`.** Never a mixed unit, never a formatted string.
 *    The unit preference is a display choice stored against the project, and formatting
 *    happens at the very edge, in `:core:units`.
 * 2. **Deletes cascade down the ownership chain.** A project owns its levels, a level its
 *    rooms, a room its corners. Orphaned corners are not a state the app should be able
 *    to reach, so the database refuses to reach it rather than trusting every call site.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Stored as the enum name, so a new unit system does not renumber the old ones. */
    val unitSystem: String,
    /**
     * Whatever the user needs to recognise this plan by — a client, an address, a flat
     * number — docs/PRODUCT_PLAN.md M13.
     *
     * One free-text field rather than a `client` column and an `address` column and a
     * `notes` column. Which of those someone needs is not knowable in advance: an estate
     * agent wants the address, a fitter wants the customer, and a person measuring their
     * own home wants "the old flat". Three columns would force everyone into a shape
     * chosen for somebody else, and two of them would sit empty for every user.
     *
     * Searched alongside the name. Empty until someone fills it in, which most will not.
     */
    val reference: String = "",
)

/**
 * A storey. Unused until multi-storey capture, and present now on purpose: adding a
 * table between projects and rooms later would be a migration over real user data, and
 * an empty layer costs one row per project.
 */
@Entity(
    tableName = "levels",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId")],
)
data class LevelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val name: String,
    val elevation: Double,
)

/**
 * A captured room.
 *
 * Area, perimeter and misclosure are **denormalised** onto this row. They are derivable
 * from the corners, but the project list shows them for every room at once and reading
 * back every corner of every room to render a list would be the one query that gets slow
 * as a user accumulates work.
 */
@Entity(
    tableName = "rooms",
    foreignKeys = [
        ForeignKey(
            entity = LevelEntity::class,
            parentColumns = ["id"],
            childColumns = ["levelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("levelId")],
)
data class RoomEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val levelId: Long,
    val name: String,
    val ceilingHeight: Double? = null,
    /**
     * Which AR session captured this room.
     *
     * Load-bearing, not bookkeeping. A room's corners are in the world frame of the
     * ARCore session that captured it, and every session starts a new frame wherever the
     * phone happened to be. Two rooms from the same session are positioned correctly
     * relative to each other and nothing else can be assumed — so the app has to know
     * which rooms those are before it draws them on one plan.
     *
     * Empty for rooms saved before this was recorded.
     */
    val captureSession: String = "",
    /** Placement within the project frame; identity until multi-room assembly (M8). */
    val originX: Double = 0.0,
    val originY: Double = 0.0,
    val rotation: Double = 0.0,
    @ColumnInfo(name = "areaSquareMetres") val area: Double,
    @ColumnInfo(name = "perimeterMetres") val perimeter: Double,
    /** Misclosure as a fraction of perimeter, kept so the plan can stay honest later. */
    val misclosure: Double,
    val isReliable: Boolean,
    val createdAt: Long,
)

/**
 * A room corner, already projected onto the floor plane, so it is 2D.
 *
 * **Two positions are kept, and the distinction matters.** `x, y` is where the solve put
 * the corner and is what the plan draws. `measuredX, measuredY` is where it was observed,
 * and is what every later solve starts from.
 *
 * Re-solving from the previous *solution* looks equivalent and is not. The direction
 * constraints never fully win against the position residuals, so each pass moves the
 * corners a little further towards perfect right angles — measured at 2.8 mm per re-solve
 * on a four-corner room. Every edit would then quietly shift walls the user never
 * touched, and after enough edits the plan would describe an idealised rectangle rather
 * than the room. Anchoring to the observations makes a re-solve idempotent.
 *
 * [sigma] is not decoration either: it is the per-point uncertainty from multi-frame
 * sampling and becomes the position-residual weight in that solve (docs/ACCURACY.md M8).
 */
@Entity(
    tableName = "corners",
    foreignKeys = [
        ForeignKey(
            entity = RoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["roomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("roomId")],
)
data class CornerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roomId: Long,
    @ColumnInfo(name = "cornerIndex") val index: Int,
    /** Where the solve put this corner. This is what gets drawn. */
    val x: Double,
    val y: Double,
    /** Where it was actually observed. This is what gets re-solved. */
    val measuredX: Double,
    val measuredY: Double,
    val sigma: Double,
    val isSnapped: Boolean = false,
    val isLocked: Boolean = false,
)

/**
 * A wall, which exists in the database only once it carries information the geometry
 * cannot supply — today, a length the user has measured by hand and typed in.
 *
 * Walls are otherwise implicit: wall *i* runs from corner *i* to corner *i+1*, so
 * storing a row per wall by default would be storing something already known. A row
 * appears when the user locks a length and disappears when they unlock it.
 *
 * That locked length is the most valuable number in the room. It is the one measurement
 * taken with a tape rather than a camera, and the solver treats it as near-certain, so
 * the whole plan tightens around it (docs/ACCURACY.md M8).
 */
@Entity(
    tableName = "walls",
    foreignKeys = [
        ForeignKey(
            entity = RoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["roomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["roomId", "wallIndex"], unique = true)],
)
data class WallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roomId: Long,
    /** Wall *i* runs from corner *i* to corner *i+1*, wrapping at the end. */
    @ColumnInfo(name = "wallIndex") val index: Int,
    /** The true length the user typed, in metres. */
    val lockedLength: Double,
)

/**
 * A door or window in a wall.
 *
 * Attached to a wall by index rather than to a `walls` row, because most walls have no
 * row: one only appears when a length is locked. Index is the stable identifier for a
 * wall either way — wall *i* runs from corner *i* to corner *i+1* — so this is the same
 * convention used everywhere else and needs no row to exist first.
 *
 * The position is measured **along the wall**, which is how a person measures it and what
 * survives the corners moving when the room is re-solved. Room coordinates would mean
 * every solve quietly slid the doors along the walls.
 */
@Entity(
    tableName = "openings",
    foreignKeys = [
        ForeignKey(
            entity = RoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["roomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("roomId")],
)
data class OpeningEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roomId: Long,
    @ColumnInfo(name = "wallIndex") val index: Int,
    /** DOOR, WINDOW or PASSAGE, stored by name. */
    val kind: String,
    /** Distance from the wall's starting corner to the near edge, in metres. */
    val offset: Double,
    val width: Double,
    val height: Double,
    val sillHeight: Double,
)

/**
 * A standalone measurement, attached to a project rather than to a room.
 *
 * Deliberately independent: "will this sofa fit" is a one-off distance with no room
 * involved, and requiring the user to create a room first would be the wrong shape for
 * the most common thing the app is opened to do.
 */
@Entity(
    tableName = "measurements",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId")],
)
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    /** The measurement mode it was taken in: FREE, HORIZONTAL or VERTICAL. */
    val mode: String,
    val fromX: Double,
    val fromY: Double,
    val fromZ: Double,
    val toX: Double,
    val toY: Double,
    val toZ: Double,
    @ColumnInfo(name = "valueMetres") val metres: Double,
    @ColumnInfo(name = "sigmaMetres") val sigma: Double,
    val label: String? = null,
    val createdAt: Long,
)

/**
 * A distance drawn on a saved plan — docs/PRODUCT_PLAN.md M12.
 *
 * Each end is stored as **what it is attached to**, not as a pair of coordinates. This is
 * the same decision [OpeningEntity] makes and for the same reason: rooms move. Locking a
 * wall or dragging a corner re-solves the whole polygon, and a measurement pinned to
 * absolute coordinates would carry on displaying a number while no longer pointing at the
 * thing it was measuring. Anchored, it moves with the wall it was taken from.
 *
 * `kind` is CORNER, WALL or FREE. `index` is the corner or wall it holds, `t` how far
 * along that wall, and `x`/`y` are used only by a free end. Flattened into columns rather
 * than serialised, so a future query can reach inside them.
 */
@Entity(
    tableName = "plan_measurements",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId")],
)
data class PlanMeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val fromKind: String,
    val fromRoomId: Long?,
    val fromIndex: Int,
    val fromT: Double,
    val fromX: Double,
    val fromY: Double,
    val toKind: String,
    val toRoomId: Long?,
    val toIndex: Int,
    val toT: Double,
    val toX: Double,
    val toY: Double,
    val label: String? = null,
    val createdAt: Long,
)
