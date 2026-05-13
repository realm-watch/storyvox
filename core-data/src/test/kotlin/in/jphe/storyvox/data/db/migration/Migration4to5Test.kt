package `in`.jphe.storyvox.data.db.migration

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import `in`.jphe.storyvox.data.db.StoryvoxDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #116 — verifies the v4 → v5 migration produces the same schema
 * Room expects when we open a fresh v5 database, and that the new
 * `fiction_shelf` table accepts roundtrips after the migration runs.
 *
 * `MigrationTestHelper` reads schema JSON from `core-data/schemas/` —
 * exported by the Room compiler at every build, wired into the test
 * classpath via `core-data/build.gradle.kts`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration4to5Test {

    private val testDbName = "shelves-migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StoryvoxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate4to5_addsFictionShelfTable_andAcceptsRoundtrips() {
        // Open v4 and seed a fiction row so the FK target exists when we
        // insert the shelf row after the migration.
        helper.createDatabase(testDbName, 4).use { db ->
            db.execSQL(
                """
                INSERT INTO fiction (
                    id, sourceId, title, author,
                    firstSeenAt, metadataFetchedAt,
                    inLibrary, followedRemotely,
                    chapterCount, notesEverSeen,
                    genres, tags, status
                ) VALUES (
                    'f1', 'royalroad', 'Sky Pride', 'Anonymous',
                    0, 0,
                    1, 0,
                    0, 0,
                    '', '', 'ONGOING'
                )
                """.trimIndent(),
            )
        }

        // Run the migration, validating that Room's expected v5 schema matches.
        val migrated = helper.runMigrationsAndValidate(testDbName, 5, true, MIGRATION_4_5)
        migrated.use { db ->
            db.execSQL(
                "INSERT INTO fiction_shelf (fictionId, shelf, addedAt) VALUES ('f1', 'Reading', 123)",
            )
            db.query("SELECT fictionId, shelf, addedAt FROM fiction_shelf WHERE fictionId = 'f1'").use { c ->
                assertNotNull(c)
                assertEquals(true, c.moveToFirst())
                assertEquals("f1", c.getString(0))
                assertEquals("Reading", c.getString(1))
                assertEquals(123L, c.getLong(2))
            }
        }
    }

    /**
     * Sanity check that opening the real Room builder with the full
     * migration array succeeds end-to-end after the v4→v5 step ran. This
     * is the path the app actually takes on a first launch after upgrade.
     */
    @Test
    fun openWithAllMigrations_afterUpgrade_succeeds() {
        helper.createDatabase(testDbName, 4).close()

        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StoryvoxDatabase::class.java,
            testDbName,
        )
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        // Touching a DAO forces Room to actually open + validate the DB.
        db.fictionShelfDao()
        db.close()
    }
}
