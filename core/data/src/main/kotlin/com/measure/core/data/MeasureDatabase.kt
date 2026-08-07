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
        PlanMeasurementEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class MeasureDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun levelDao(): LevelDao
    abstract fun roomDao(): RoomDao
    abstract fun wallDao(): WallDao
    abstract fun openingDao(): OpeningDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun planMeasurementDao(): PlanMeasurementDao

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

        /**
         * Adds distances drawn on the plan. A new table, so nothing existing is touched.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `plan_measurements` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `projectId` INTEGER NOT NULL,
                        `fromKind` TEXT NOT NULL,
                        `fromRoomId` INTEGER,
                        `fromIndex` INTEGER NOT NULL,
                        `fromT` REAL NOT NULL,
                        `fromX` REAL NOT NULL,
                        `fromY` REAL NOT NULL,
                        `toKind` TEXT NOT NULL,
                        `toRoomId` INTEGER,
                        `toIndex` INTEGER NOT NULL,
                        `toT` REAL NOT NULL,
                        `toX` REAL NOT NULL,
                        `toY` REAL NOT NULL,
                        `label` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """,
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_plan_measurements_projectId` " +
                        "ON `plan_measurements` (`projectId`)",
                )
            }
        }

        /**
         * Records which AR session captured each room.
         *
         * Existing rooms get an empty session, which reads as "unknown" — the app must
         * not claim two old rooms were captured together when it has no way to tell.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE `rooms` ADD COLUMN `captureSession` TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /**
         * Adds the free-text reference a plan can be found by.
         *
         * Empty for every existing plan, which is exactly right: the app has no idea whose
         * house "Plan 3" is, and inventing one would be worse than leaving it blank.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE `projects` ADD COLUMN `reference` TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /**
         * Adds how a door is hung, and a description for a plan measurement.
         *
         * Two unrelated columns in one migration because they were wanted in the same
         * change, and a version bump is not free: every extra version is another starting
         * point every future migration has to be tested from.
         *
         * Both default to "absent" rather than to a guess. An existing door gets the empty
         * string, which [DoorSwing.parse] reads as the default hanging — and the default is
         * exactly what the app drew before doors could be hung, so no plan changes
         * appearance on upgrade. An existing measurement gets a null description, which is
         * distinct from an empty one: nobody wrote anything, as opposed to writing nothing.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `openings` ADD COLUMN `swing` TEXT NOT NULL DEFAULT ''")
                connection.execSQL("ALTER TABLE `plan_measurements` ADD COLUMN `description` TEXT")
            }
        }

        /**
         * Every migration, in order, as one list.
         *
         * A single list rather than six arguments spelled out at the call site, because the
         * migration test has to be able to register exactly what the app registers. Given
         * two lists, a migration could be written, tested, and then left out of the builder
         * — and the test would still pass while upgrades on real phones crashed.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
        )

        @Volatile
        private var instance: MeasureDatabase? = null

        fun get(context: Context): MeasureDatabase =
            instance ?: synchronized(this) {
                instance ?: builder(context.applicationContext).build().also { instance = it }
            }

        /**
         * How the app opens its database — and, deliberately, how the migration test opens
         * it too.
         *
         * Exposed rather than inlined into [get] so that the test upgrading a v1 file is
         * upgrading it through this configuration, not through a second one written to
         * resemble it. Journal mode and the migration list both affect whether an upgrade
         * succeeds, so a test that reconstructed them separately would be testing its own
         * copy of the setup.
         */
        internal fun builder(
            context: Context,
            name: String = NAME,
        ): RoomDatabase.Builder<MeasureDatabase> =
            Room.databaseBuilder(context, MeasureDatabase::class.java, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // Nothing here switches foreign keys on, and they are on: Room enables them
                // itself when it opens a database. This line used to carry a comment saying
                // the opposite, which mattered because the cascading deletes declared on the
                // entities are load-bearing — orphaned corners are not a state the app
                // should be able to reach. `MigrationTest.deletesStillCascadeAfterAnUpgrade`
                // now establishes that on an upgraded file rather than leaving it asserted
                // in a comment.
                .addMigrations(*MIGRATIONS)
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

    /**
     * Substitutes the repository, for tests. Pass null to go back to the real one.
     *
     * The seam that "no dependency injection until there is a graph worth wiring" had been
     * deferring. It came due for a specific reason: a whole class of interface fault —
     * panels that silently stop reflecting the model — is invisible to every test this
     * project can currently run, and it has reached the user three times. Catching it needs
     * the editor rendered against a database somebody can seed, and every view model here
     * builds its own repository from this object.
     *
     * A settable instance rather than Hilt, because one substitution point is not a graph
     * either. `TECHNICAL_DESIGN.md` still names Hilt as the eventual answer, and this is the
     * same seam it would slot into.
     */
    fun useForTesting(repository: MeasureRepository?) {
        synchronized(this) { this.repository = repository }
    }
}
