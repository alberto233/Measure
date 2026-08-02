package com.measure.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

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
        WallEntity::class,
        OpeningEntity::class,
        MeasurementEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class MeasureDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun levelDao(): LevelDao
    abstract fun roomDao(): RoomDao
    abstract fun wallDao(): WallDao
    abstract fun openingDao(): OpeningDao
    abstract fun measurementDao(): MeasurementDao

    companion object {
        private const val NAME = "measure.db"

        /**
         * Adds the walls table — the repository's first migration.
         *
         * Written by hand and deliberately, rather than reached for with
         * `fallbackToDestructiveMigration`, because by the time this shipped there were
         * already plans on a phone. Destroying someone's measured rooms to add a column
         * is not a trade this app gets to make: re-measuring a room means walking it
         * again with a tape, which is the very work the app exists to save.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `walls` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `roomId` INTEGER NOT NULL,
                        `wallIndex` INTEGER NOT NULL,
                        `lockedLength` REAL NOT NULL,
                        FOREIGN KEY(`roomId`) REFERENCES `rooms`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """,
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_walls_roomId_wallIndex` " +
                        "ON `walls` (`roomId`, `wallIndex`)",
                )
            }
        }

        /**
         * Records what was observed, alongside what the solve produced.
         *
         * Existing rows only ever held the solved position, so it is copied across as the
         * best available record of the measurement. That makes those rooms behave exactly
         * as they did before — they re-solve from their own solution — while every room
         * captured afterwards re-solves from the observations, which is the point.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `corners` ADD COLUMN `measuredX` REAL NOT NULL DEFAULT 0.0")
                connection.execSQL("ALTER TABLE `corners` ADD COLUMN `measuredY` REAL NOT NULL DEFAULT 0.0")
                connection.execSQL("UPDATE `corners` SET `measuredX` = `x`, `measuredY` = `y`")
            }
        }

        /** Adds doors and windows. Nothing existing changes, so nothing needs copying. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `openings` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `roomId` INTEGER NOT NULL,
                        `wallIndex` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `offset` REAL NOT NULL,
                        `width` REAL NOT NULL,
                        `height` REAL NOT NULL,
                        `sillHeight` REAL NOT NULL,
                        FOREIGN KEY(`roomId`) REFERENCES `rooms`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """,
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_openings_roomId` ON `openings` (`roomId`)",
                )
            }
        }

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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
