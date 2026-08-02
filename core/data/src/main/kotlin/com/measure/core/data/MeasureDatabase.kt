package com.measure.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The on-device store. Nothing leaves the phone — docs/PRODUCT_PLAN.md §5.
 *
 * `exportSchema` is on and the schemas are checked in. That is the whole point of using
 * a relational store here rather than serialising objects to a file: once a user has
 * saved work, every change to this schema has to be a migration they survive, and the
 * exported JSON is what makes writing and testing those migrations possible at all.
 */
@Database(
    entities = [
        ProjectEntity::class,
        LevelEntity::class,
        RoomEntity::class,
        CornerEntity::class,
        MeasurementEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MeasureDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun levelDao(): LevelDao
    abstract fun roomDao(): RoomDao
    abstract fun measurementDao(): MeasurementDao

    companion object {
        private const val NAME = "measure.db"

        @Volatile
        private var instance: MeasureDatabase? = null

        fun get(context: Context): MeasureDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): MeasureDatabase =
            Room.databaseBuilder(context, MeasureDatabase::class.java, NAME)
                // Cascading deletes are declared on the entities and are load-bearing:
                // Room does not switch foreign keys on for you.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}

/**
 * How the rest of the app gets a repository.
 *
 * A plain object rather than dependency injection. The technical design specifies Hilt,
 * and this is the seam it will slot into — one place that knows how a repository is
 * constructed. Introducing a DI framework to hand out a single object would be ceremony
 * ahead of need; introducing it once there is a graph worth wiring is the right moment.
 */
object MeasureData {

    @Volatile
    private var repository: MeasureRepository? = null

    fun repository(context: Context): MeasureRepository =
        repository ?: synchronized(this) {
            repository ?: MeasureRepository(MeasureDatabase.get(context)).also { repository = it }
        }
}
