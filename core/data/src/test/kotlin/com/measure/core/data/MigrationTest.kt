package com.measure.core.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Does the upgrade actually work?
 *
 * The seven migrations in [MeasureDatabase] were written by hand and, until this file,
 * none had ever been run against a database containing anything. Room compiles them, so
 * the SQL was known to parse; whether a phone holding real measurements arrives at v7 with
 * those measurements intact was not known at all — it was inferred from reading the SQL.
 *
 * That gap matters more here than in most apps. A row in this database is a room somebody
 * walked with a phone, or a wall they measured with a tape and typed in. Losing it is not
 * a cache miss they will not notice; it is asking them to go and do the work again. The
 * project's stated position (docs/TECHNICAL_DESIGN.md §2, and the comment on MIGRATION_1_2)
 * is that destructive migration is not a trade this app gets to make. This is the test
 * that makes that position checkable rather than merely stated.
 *
 * Four distinct things are checked, and they fail for different reasons:
 *
 *  - **Every shipped version still has a checked-in schema.** Without the JSON for a
 *    version, no test can construct a database as that version looked, so that version's
 *    upgrade is untestable by anyone, forever. `6.json` was in fact missing.
 *  - **Every shipped version reaches v7.** Users do not upgrade one release at a time.
 *    Someone who last opened the app at v2 jumps straight to v7, and every starting point
 *    has to land on a schema Room accepts.
 *  - **The data survives.** Room validates structure, not content. A migration can produce
 *    a perfectly valid v7 schema with every room silently emptied, and `runMigrationsAndValidate`
 *    will pass it.
 *  - **The app can then open and read it.** The step past schema validation: the real
 *    builder, the real DAOs, the real cascade behaviour, on a file that arrived at v7 by
 *    being upgraded rather than by being created there.
 *
 * Robolectric rather than an emulator, for the same reason as the editor's tests: this runs
 * on the JVM in CI, and a suite that needs a device is a suite that stops being run.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * The file every test in here upgrades. Named and placed where Room would put it, so
     * the later tests can hand the same file to a real [MeasureDatabase] by name.
     */
    private val databaseFile: File = context.getDatabasePath(DB_NAME)

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = databaseFile,
        driver = AndroidSQLiteDriver(),
        databaseClass = MeasureDatabase::class,
    )

    /**
     * The rule clears the database itself, but not its write-ahead log.
     *
     * The app runs in WAL mode, so an upgraded database is three files. A stale `-wal`
     * left beside a freshly created `-shm` is its own source of confusing failures, and
     * they would land on whichever test happened to run next.
     */
    @Before
    fun clearAnyPreviousDatabase() {
        deleteDatabaseFiles()
    }

    private fun deleteDatabaseFiles() {
        for (suffix in listOf("", "-wal", "-shm")) {
            File(databaseFile.path + suffix).delete()
        }
    }

    /**
     * Every version that has ever existed must have its schema checked in.
     *
     * This is the cheapest test here and it is the one that already caught something:
     * `6.json` had never been exported. The version was bumped to 6 and the generated
     * schema was not committed with it, so the v5→v6 and v6→v7 hops could not be tested by
     * anybody — `createDatabase(6)` has nothing to build a v6 database from.
     *
     * It fails the moment someone bumps the version and forgets to commit what KSP wrote,
     * which is exactly when it is cheap to fix. Discovered two releases later, the only
     * honest way to recover the file is to check out the old commit and rebuild it.
     */
    @Test
    fun everyShippedVersionHasACheckedInSchema() {
        val missing = (1..CURRENT_VERSION).filterNot { version ->
            runCatching {
                InstrumentationRegistry.getInstrumentation().context.assets
                    .open("${MeasureDatabase::class.java.canonicalName}/$version.json")
                    .close()
            }.isSuccess
        }

        assertEquals(
            "No exported schema for these versions, so their upgrades cannot be tested. " +
                "Re-export by building at the commit that introduced each, and commit the JSON.",
            emptyList<Int>(),
            missing,
        )
    }

    /**
     * The version this test walks to is the version the app actually ships.
     *
     * Every assertion below is written against v7. If someone adds v8 and does not touch
     * this file, all of it would carry on passing while testing an upgrade path that stops
     * one version short of where phones end up — the failure mode this whole file exists to
     * prevent, reintroduced by omission.
     *
     * Read off a database Room has just created rather than off the annotation, which is
     * declared with binary retention and cannot be read back at runtime.
     */
    @Test
    fun theCurrentVersionIsTheOneThisTestWalksTo() {
        val probeName = "version-probe.db"
        val probe = context.getDatabasePath(probeName)
        probe.delete()

        val database = MeasureDatabase.builder(context, probeName).build()
        try {
            runBlocking { database.projectDao().allNames() }
        } finally {
            database.close()
        }

        AndroidSQLiteDriver().open(probe.absolutePath).use { connection ->
            connection.prepare("PRAGMA user_version").use { statement ->
                statement.step()
                assertEquals(
                    "This test walks to v$CURRENT_VERSION but the app now ships a later " +
                        "version. Extend the seed data and the assertions to cover it.",
                    CURRENT_VERSION,
                    statement.getLong(0).toInt(),
                )
            }
        }
    }

    /**
     * The migration list the app registers has to cover every hop with no gaps.
     *
     * Room only reports a missing migration when a device with that exact version tries to
     * open the database, which on a developer's machine is never — their database was
     * created at the current version. The first person to find out is a user whose app
     * crashes on launch after an update.
     */
    @Test
    fun theRegisteredMigrationsCoverEveryHop() {
        val hops = MeasureDatabase.MIGRATIONS
            .map { it.startVersion to it.endVersion }
            .sortedBy { it.first }

        assertEquals(
            "The registered migrations must form an unbroken chain from 1 to $CURRENT_VERSION.",
            (1 until CURRENT_VERSION).map { it to it + 1 },
            hops,
        )
    }

    /**
     * Every version the app has shipped must reach v7.
     *
     * Not just v6→v7. A phone that has sat unopened since v2 upgrades straight to v7 in one
     * launch, and Room runs the intervening migrations back to back on a database whose
     * content none of them were written while looking at.
     *
     * [MigrationTestHelper.runMigrationsAndValidate] compares the result against the
     * checked-in schema for v7, so this catches the migration that produces *nearly* the
     * right table — a missing index, a column with the wrong affinity, a foreign key that
     * was not recreated. Room would refuse to open such a database on the phone, having
     * accepted it here at compile time.
     */
    @Test
    fun everyShippedVersionUpgradesToTheCurrentOne() {
        for (startVersion in 1 until CURRENT_VERSION) {
            // Each starting point needs the file gone, not merely closed: the previous
            // iteration left it at v7, and creating v2 on top of that is a downgrade.
            deleteDatabaseFiles()

            helper.createDatabase(startVersion).close()
            helper.runMigrationsAndValidate(
                CURRENT_VERSION,
                MeasureDatabase.MIGRATIONS.toList(),
            ).close()
        }
    }

    /**
     * The measurements come through the whole walk intact.
     *
     * Schema validation is blind to this. Every migration here could drop its table and
     * recreate it empty and `runMigrationsAndValidate` would be satisfied, because what it
     * checks is that v7 *looks* like v7.
     *
     * So the database is seeded as a real v1 install — a flat with two measured rooms and a
     * standalone distance — walked all the way to v7, and read back value by value.
     */
    @Test
    fun measurementsSurviveTheWalkFromTheFirstVersion() {
        helper.createDatabase(1).use { seedVersionOne(it) }

        val migrated = helper.runMigrationsAndValidate(
            CURRENT_VERSION,
            MeasureDatabase.MIGRATIONS.toList(),
        )

        migrated.use { db ->
            assertEquals(
                "The project's name did not survive the upgrade.",
                listOf("Flat 3, Alder Road"),
                db.textColumn("SELECT name FROM projects ORDER BY id"),
            )
            assertEquals(
                listOf("Living room", "Kitchen"),
                db.textColumn("SELECT name FROM rooms ORDER BY id"),
            )
            assertEquals(
                "The denormalised areas are what the project list draws; losing them " +
                    "would empty every card.",
                listOf(20.0, 8.0),
                db.doubleColumn("SELECT areaSquareMetres FROM rooms ORDER BY id"),
            )
            assertEquals(
                listOf(18.0, 11.4),
                db.doubleColumn("SELECT perimeterMetres FROM rooms ORDER BY id"),
            )
            assertEquals(
                "Eight corners went in; the living room's four and the kitchen's four.",
                8,
                db.count("SELECT COUNT(*) FROM corners"),
            )
            assertEquals(
                listOf(0.0, 5.0, 5.0, 0.0),
                db.doubleColumn(
                    "SELECT x FROM corners WHERE roomId = 1 ORDER BY cornerIndex",
                ),
            )
            assertEquals(
                "The standalone measurement — the 'will the sofa fit' case — is attached " +
                    "to the project rather than to a room, and is easy to forget.",
                listOf(2.14),
                db.doubleColumn("SELECT valueMetres FROM measurements"),
            )
            assertEquals(
                "A null label has to stay null rather than becoming an empty string.",
                1,
                db.count("SELECT COUNT(*) FROM measurements WHERE label IS NULL"),
            )
        }
    }

    /**
     * v2→v3 has to leave old rooms re-solving exactly as they did before.
     *
     * The only migration in this database that moves data rather than adding somewhere to
     * put it, and the only one where a plausible-looking result is wrong.
     *
     * v3 split a corner into where the solve put it (`x`, `y`) and where it was observed
     * (`measuredX`, `measuredY`), because every later solve re-solves from the observations
     * — anchoring to the previous solution makes each edit creep towards an idealised
     * rectangle (see [CornerEntity]). Rooms captured before v3 have no record of what was
     * observed, so the migration copies the solved position across as the best available
     * stand-in.
     *
     * Leaving the columns at their `0.0` default would satisfy Room completely and collapse
     * every pre-v3 room onto the origin the first time it was edited. The whole room would
     * fold to a point.
     */
    @Test
    fun roomsCapturedBeforeVersionThreeReSolveFromTheirOwnSolution() {
        helper.createDatabase(2).use { db ->
            db.execSQL(
                "INSERT INTO projects (id, name, createdAt, updatedAt, unitSystem) " +
                    "VALUES (1, 'Survey', 100, 100, 'METRIC')",
            )
            db.execSQL("INSERT INTO levels (id, projectId, name, elevation) VALUES (1, 1, 'Ground', 0.0)")
            db.execSQL(
                "INSERT INTO rooms (id, levelId, name, ceilingHeight, originX, originY, rotation, " +
                    "areaSquareMetres, perimeterMetres, misclosure, isReliable, createdAt) " +
                    "VALUES (1, 1, 'Study', 2.4, 0.0, 0.0, 0.0, 12.0, 14.0, 0.004, 1, 100)",
            )
            // Deliberately not on the origin, and not round: a corner at (0, 0) would pass
            // this test even if the migration copied nothing at all.
            db.execSQL(
                "INSERT INTO corners (id, roomId, cornerIndex, x, y, sigma, isSnapped, isLocked) " +
                    "VALUES (1, 1, 0, 3.271, -1.884, 0.021, 1, 0)",
            )
        }

        helper.runMigrationsAndValidate(3, MeasureDatabase.MIGRATIONS.toList()).use { db ->
            assertEquals(
                "The observed position must be back-filled from the solved one, or this " +
                    "room collapses onto the origin the first time it is re-solved.",
                listOf(3.271, -1.884),
                db.doubleColumn("SELECT measuredX, measuredY FROM corners WHERE id = 1"),
            )
        }
    }

    /**
     * The new columns arrive empty rather than invented.
     *
     * Both are cases where a confident-looking default would be a lie the interface then
     * repeats. `captureSession` decides which rooms may be drawn on one plan — rooms from
     * different AR sessions share no coordinate frame — so giving old rooms a shared
     * session would have the app assemble a floor plan out of rooms it has no reason to
     * believe line up. `reference` is the free-text field a plan is found by; the app has
     * no idea whose house "Plan 3" is.
     */
    @Test
    fun columnsAddedLaterArriveEmpty() {
        helper.createDatabase(1).use { seedVersionOne(it) }

        helper.runMigrationsAndValidate(
            CURRENT_VERSION,
            MeasureDatabase.MIGRATIONS.toList(),
        ).use { db ->
            assertEquals(
                "An old room's capture session is unknown, and must read as unknown.",
                listOf("", ""),
                db.textColumn("SELECT captureSession FROM rooms ORDER BY id"),
            )
            assertEquals(
                listOf(""),
                db.textColumn("SELECT reference FROM projects"),
            )
            assertEquals(0, db.count("SELECT COUNT(*) FROM walls"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM openings"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM plan_measurements"))
        }
    }

    /**
     * The app opens the upgraded file and reads it.
     *
     * Everything above stops at the schema. This is the part that matters to a user: a
     * phone that had v1 on it, upgraded, and can now see its rooms. It goes through
     * [MeasureDatabase.builder] — the same configuration [MeasureDatabase.get] uses — so
     * the migration list, the journal mode and the identity-hash check are the real ones,
     * and the reads go through the actual DAOs rather than through SQL written for the test.
     *
     * Room re-checks the schema hash on open, so this also catches the case where a
     * migration produces something `runMigrationsAndValidate` tolerates but the runtime
     * does not.
     */
    @Test
    fun theAppOpensAndReadsADatabaseItUpgraded() {
        helper.createDatabase(1).use { seedVersionOne(it) }

        // No explicit migration step: Room finds the file at v1 and upgrades it on open,
        // which is precisely what happens on a phone after an update.
        val database = MeasureDatabase.builder(context, DB_NAME).build()

        try {
            runBlocking {
                val summaries = database.projectDao().observeSummaries().first()
                assertEquals(1, summaries.size)
                assertEquals("Flat 3, Alder Road", summaries[0].name)
                assertEquals("", summaries[0].reference)
                assertEquals(2, summaries[0].roomCount)
                assertEquals(1, summaries[0].measurementCount)
                assertEquals(28.0, summaries[0].totalArea, 1e-9)

                val rooms = database.roomDao().roomsIn(summaries[0].id)
                assertEquals(listOf("Living room", "Kitchen"), rooms.map { it.name })
                assertEquals(2.4, rooms[0].ceilingHeight!!, 1e-9)

                val corners = database.roomDao().cornersFor(rooms[0].id)
                assertEquals(4, corners.size)
                assertTrue(
                    "A corner read back through the DAO must carry the observed position " +
                        "the migration back-filled, not a zero.",
                    corners.all { it.measuredX == it.x && it.measuredY == it.y },
                )

                // A write against the upgraded file, because an upgrade that produces a
                // readable but unwritable database is still a broken upgrade — and this
                // column did not exist in the file being written to until the migration.
                database.projectDao().setReference(summaries[0].id, "Ms Okafor", 200)
                assertEquals(
                    "Ms Okafor",
                    database.projectDao().find(summaries[0].id)!!.reference,
                )
            }
        } finally {
            database.close()
        }
    }

    /**
     * Deletes still cascade on an upgraded file.
     *
     * Worth its own test because foreign keys are the one guarantee here that lives in the
     * database rather than in Kotlin. [Entities.kt] states it plainly: orphaned corners are
     * not a state the app should be able to reach, so the database refuses to reach it
     * rather than every call site being trusted to.
     *
     * A migration is exactly how that guarantee gets lost without anyone noticing. Recreate
     * a table without its foreign key and everything keeps working — reads, writes,
     * schema validation — right up until a project is deleted and its rooms stay behind,
     * invisible, attached to nothing.
     */
    @Test
    fun deletesStillCascadeAfterAnUpgrade() {
        helper.createDatabase(1).use { seedVersionOne(it) }

        val database = MeasureDatabase.builder(context, DB_NAME).build()

        try {
            runBlocking {
                val projectId = database.projectDao().observeSummaries().first().single().id
                assertEquals(2, database.roomDao().roomsIn(projectId).size)

                database.projectDao().delete(projectId)

                assertEquals(
                    "Deleting the project must take its levels, rooms and corners with it.",
                    emptyList<RoomEntity>(),
                    database.roomDao().roomsIn(projectId),
                )
                assertEquals(
                    "Corners outlive their room only if the cascade was lost in migration.",
                    emptyList<CornerEntity>(),
                    database.roomDao().cornersIn(projectId),
                )
            }
        } finally {
            database.close()
        }
    }

    /**
     * A v1 database with real work in it: a flat with two measured rooms and one
     * standalone distance.
     *
     * Written as v1 SQL on purpose. Inserting through the entities would insert today's
     * columns, which is the one thing a migration test must not do — the point is to start
     * from the shape a user's phone is actually holding.
     */
    private fun seedVersionOne(db: SQLiteConnection) {
        db.execSQL(
            "INSERT INTO projects (id, name, createdAt, updatedAt, unitSystem) " +
                "VALUES (1, 'Flat 3, Alder Road', 1700000000000, 1700000000000, 'METRIC')",
        )
        db.execSQL(
            "INSERT INTO levels (id, projectId, name, elevation) VALUES (1, 1, 'Ground', 0.0)",
        )

        // 5.0 × 4.0 — area 20.0, perimeter 18.0.
        db.execSQL(
            "INSERT INTO rooms (id, levelId, name, ceilingHeight, originX, originY, rotation, " +
                "areaSquareMetres, perimeterMetres, misclosure, isReliable, createdAt) " +
                "VALUES (1, 1, 'Living room', 2.4, 0.0, 0.0, 0.0, 20.0, 18.0, 0.006, 1, 1700000000000)",
        )
        // 3.2 × 2.5 — area 8.0, perimeter 11.4.
        db.execSQL(
            "INSERT INTO rooms (id, levelId, name, ceilingHeight, originX, originY, rotation, " +
                "areaSquareMetres, perimeterMetres, misclosure, isReliable, createdAt) " +
                "VALUES (2, 1, 'Kitchen', 2.4, 0.0, 0.0, 0.0, 8.0, 11.4, 0.011, 1, 1700000001000)",
        )

        val livingRoom = listOf(0.0 to 0.0, 5.0 to 0.0, 5.0 to 4.0, 0.0 to 4.0)
        val kitchen = listOf(0.0 to 0.0, 3.2 to 0.0, 3.2 to 2.5, 0.0 to 2.5)
        var cornerId = 1
        for ((roomId, corners) in listOf(1 to livingRoom, 2 to kitchen)) {
            corners.forEachIndexed { index, (x, y) ->
                db.execSQL(
                    "INSERT INTO corners (id, roomId, cornerIndex, x, y, sigma, isSnapped, isLocked) " +
                        "VALUES (${cornerId++}, $roomId, $index, $x, $y, 0.018, 1, 0)",
                )
            }
        }

        // Label left null — the column is nullable and a migration that coalesced it to ''
        // would be a silent change to what the interface shows.
        db.execSQL(
            "INSERT INTO measurements (id, projectId, mode, fromX, fromY, fromZ, toX, toY, toZ, " +
                "valueMetres, sigmaMetres, label, createdAt) " +
                "VALUES (1, 1, 'HORIZONTAL', 0.0, 0.0, 0.0, 2.14, 0.0, 0.0, 2.14, 0.009, NULL, 1700000002000)",
        )
    }

    private fun SQLiteConnection.textColumn(sql: String): List<String> = readColumn(sql) { it.getText(0) }

    private fun SQLiteConnection.count(sql: String): Int = readColumn(sql) { it.getLong(0).toInt() }.single()

    /** Reads every column of every row, so a two-column select comes back flattened. */
    private fun SQLiteConnection.doubleColumn(sql: String): List<Double> {
        val values = mutableListOf<Double>()
        prepare(sql).use { statement ->
            while (statement.step()) {
                for (column in 0 until statement.getColumnCount()) {
                    values += statement.getDouble(column)
                }
            }
        }
        return values
    }

    private fun <T> SQLiteConnection.readColumn(
        sql: String,
        read: (androidx.sqlite.SQLiteStatement) -> T,
    ): List<T> {
        val values = mutableListOf<T>()
        prepare(sql).use { statement ->
            while (statement.step()) {
                values += read(statement)
            }
        }
        return values
    }

    private companion object {
        /**
         * Not `measure.db`. The test upgrades this file repeatedly and a name collision
         * with the real database would be a genuinely nasty accident to debug.
         */
        const val DB_NAME = "migration-test.db"

        /**
         * Stated rather than read from `@Database`, which Room declares with binary
         * retention and so is invisible to reflection at runtime.
         *
         * [theCurrentVersionIsTheOneThisTestWalksTo] holds it honest by comparing it
         * against a freshly created database, so bumping the version without extending
         * this test fails here rather than quietly testing the wrong ceiling.
         */
        const val CURRENT_VERSION = 7
    }
}
