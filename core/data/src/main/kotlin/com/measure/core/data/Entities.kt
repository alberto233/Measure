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
 * [sigma] is not decoration: it is the per-point uncertainty from multi-frame sampling
 * and becomes the position-residual weight when the room is re-solved (docs/ACCURACY.md
 * M8). Discarding it would mean a re-solve could never be as good as the original.
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
    val x: Double,
    val y: Double,
    val sigma: Double,
    val isSnapped: Boolean = false,
    val isLocked: Boolean = false,
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
