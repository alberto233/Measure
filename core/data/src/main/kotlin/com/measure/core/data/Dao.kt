package com.measure.core.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * What the project list needs, in one row per project.
 *
 * Computed by the database rather than by loading each project's rooms and counting in
 * Kotlin. The list is the screen most likely to be opened with a lot of data behind it,
 * and it is the one place where getting the query shape wrong shows up as a stutter.
 */
data class ProjectSummaryRow(
    val id: Long,
    val name: String,
    val updatedAt: Long,
    val unitSystem: String,
    val roomCount: Int,
    val measurementCount: Int,
    val totalArea: Double,
)

/** A standalone measurement reduced to a plan-view line, for list thumbnails. */
data class MeasurementLineRow(
    val projectId: Long,
    val fromX: Double,
    val fromZ: Double,
    val toX: Double,
    val toZ: Double,
    val metres: Double,
)

/** One corner of one room, tagged with its project, for drawing list thumbnails. */
data class OutlinePointRow(
    val projectId: Long,
    val roomId: Long,
    val x: Double,
    val y: Double,
)

@Dao
interface ProjectDao {

    @Query(
        """
        SELECT p.id, p.name, p.updatedAt, p.unitSystem,
               (SELECT COUNT(*) FROM rooms r
                  JOIN levels l ON r.levelId = l.id
                 WHERE l.projectId = p.id) AS roomCount,
               (SELECT COUNT(*) FROM measurements m
                 WHERE m.projectId = p.id) AS measurementCount,
               (SELECT COALESCE(SUM(r.areaSquareMetres), 0.0) FROM rooms r
                  JOIN levels l ON r.levelId = l.id
                 WHERE l.projectId = p.id) AS totalArea
          FROM projects p
         ORDER BY p.updatedAt DESC
        """,
    )
    fun observeSummaries(): Flow<List<ProjectSummaryRow>>

    /**
     * Every stored corner, tagged with its project.
     *
     * One query for the whole list rather than one per project. A user with a hundred
     * projects has a few thousand corners, which is nothing to read in one go and a great
     * deal of round trips to read one project at a time.
     */
    @Query(
        """
        SELECT l.projectId AS projectId, c.roomId AS roomId, c.x AS x, c.y AS y
          FROM corners c
          JOIN rooms r ON c.roomId = r.id
          JOIN levels l ON r.levelId = l.id
         ORDER BY l.projectId, r.createdAt, c.cornerIndex
        """,
    )
    fun observeOutlinePoints(): Flow<List<OutlinePointRow>>

    /**
     * Every standalone measurement as a plan-view line.
     *
     * A project can hold measurements and no rooms — "will the sofa fit" is exactly that
     * — and without these its card would show an empty box, which reads as lost work.
     */
    @Query(
        """
        SELECT projectId, fromX, fromZ, toX, toZ, valueMetres AS metres
          FROM measurements
         ORDER BY projectId, createdAt
        """,
    )
    fun observeMeasurementLines(): Flow<List<MeasurementLineRow>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observe(id: Long): Flow<ProjectEntity?>

    @Query("SELECT name FROM projects")
    suspend fun allNames(): List<String>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun find(id: Long): ProjectEntity?

    @Insert
    suspend fun insert(project: ProjectEntity): Long

    @Update
    suspend fun update(project: ProjectEntity)

    @Query("UPDATE projects SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE projects SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface LevelDao {
    @Insert
    suspend fun insert(level: LevelEntity): Long

    @Query("SELECT * FROM levels WHERE projectId = :projectId ORDER BY elevation LIMIT 1")
    suspend fun firstFor(projectId: Long): LevelEntity?
}

@Dao
interface RoomDao {
    @Insert
    suspend fun insert(room: RoomEntity): Long

    @Insert
    suspend fun insertCorners(corners: List<CornerEntity>)

    @Query(
        """
        SELECT r.* FROM rooms r
          JOIN levels l ON r.levelId = l.id
         WHERE l.projectId = :projectId
         ORDER BY r.createdAt
        """,
    )
    fun observeRooms(projectId: Long): Flow<List<RoomEntity>>

    @Query(
        """
        SELECT c.* FROM corners c
          JOIN rooms r ON c.roomId = r.id
          JOIN levels l ON r.levelId = l.id
         WHERE l.projectId = :projectId
         ORDER BY c.roomId, c.cornerIndex
        """,
    )
    fun observeCorners(projectId: Long): Flow<List<CornerEntity>>

    @Query("SELECT * FROM corners WHERE roomId = :roomId ORDER BY cornerIndex")
    suspend fun cornersFor(roomId: Long): List<CornerEntity>

    @Query(
        """
        SELECT r.* FROM rooms r
          JOIN levels l ON r.levelId = l.id
         WHERE l.projectId = :projectId
         ORDER BY r.createdAt
        """,
    )
    suspend fun roomsIn(projectId: Long): List<RoomEntity>

    @Query(
        """
        SELECT c.* FROM corners c
          JOIN rooms r ON c.roomId = r.id
          JOIN levels l ON r.levelId = l.id
         WHERE l.projectId = :projectId
        """,
    )
    suspend fun cornersIn(projectId: Long): List<CornerEntity>

    /** Shifts a whole room, for assembling a plan by hand. */
    @Query("UPDATE corners SET x = x + :dx, y = y + :dy, measuredX = measuredX + :dx, measuredY = measuredY + :dy WHERE roomId = :roomId")
    suspend fun translateCorners(roomId: Long, dx: Double, dy: Double)

    @Query(
        """
        SELECT r.name FROM rooms r
          JOIN levels l ON r.levelId = l.id
         WHERE l.projectId = :projectId
        """,
    )
    suspend fun namesIn(projectId: Long): List<String>

    @Query("DELETE FROM rooms WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE rooms SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE rooms SET ceilingHeight = :metres WHERE id = :id")
    suspend fun setCeilingHeight(id: Long, metres: Double?)

    @Query("DELETE FROM corners WHERE roomId = :roomId")
    suspend fun deleteCorners(roomId: Long)

    @Query(
        """
        UPDATE rooms
           SET areaSquareMetres = :area, perimeterMetres = :perimeter, isReliable = :reliable
         WHERE id = :id
        """,
    )
    suspend fun updateGeometry(id: Long, area: Double, perimeter: Double, reliable: Boolean)
}

@Dao
interface WallDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(wall: WallEntity)

    @Query("DELETE FROM walls WHERE roomId = :roomId AND wallIndex = :index")
    suspend fun unlock(roomId: Long, index: Int)

    @Query(
        """
        SELECT w.* FROM walls w
          JOIN rooms r ON w.roomId = r.id
          JOIN levels l ON r.levelId = l.id
         WHERE l.projectId = :projectId
        """,
    )
    fun observeFor(projectId: Long): Flow<List<WallEntity>>
}

@Dao
interface OpeningDao {

    @Insert
    suspend fun insert(opening: OpeningEntity): Long

    @Update
    suspend fun update(opening: OpeningEntity)

    @Query("DELETE FROM openings WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """
        SELECT o.* FROM openings o
          JOIN rooms r ON o.roomId = r.id
          JOIN levels l ON r.levelId = l.id
         WHERE l.projectId = :projectId
         ORDER BY o.roomId, o.wallIndex, o.offset
        """,
    )
    fun observeFor(projectId: Long): Flow<List<OpeningEntity>>
}

@Dao
interface MeasurementDao {
    @Insert
    suspend fun insert(measurement: MeasurementEntity): Long

    @Query("SELECT * FROM measurements WHERE projectId = :projectId ORDER BY createdAt")
    fun observeFor(projectId: Long): Flow<List<MeasurementEntity>>

    @Delete
    suspend fun delete(measurement: MeasurementEntity)

    @Query("DELETE FROM measurements WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface PlanMeasurementDao {
    @Insert
    suspend fun insert(measurement: PlanMeasurementEntity): Long

    @Query("SELECT * FROM plan_measurements WHERE projectId = :projectId ORDER BY createdAt")
    fun observeFor(projectId: Long): Flow<List<PlanMeasurementEntity>>

    @Query("DELETE FROM plan_measurements WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Removes measurements anchored to a room that is being deleted.
     *
     * A distance to a room that no longer exists is not a distance to anything, and
     * leaving it on the plan pointing into space would be worse than losing it.
     */
    @Query("DELETE FROM plan_measurements WHERE fromRoomId = :roomId OR toRoomId = :roomId")
    suspend fun deleteForRoom(roomId: Long)
}
