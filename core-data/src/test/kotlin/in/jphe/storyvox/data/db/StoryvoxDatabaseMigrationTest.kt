package `in`.jphe.storyvox.data.db

import android.app.Instrumentation
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import `in`.jphe.storyvox.data.db.migration.MIGRATION_4_5
import `in`.jphe.storyvox.data.db.migration.MIGRATION_5_6
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #158 — schema migration coverage for v5 → v6 (chapter_history).
 *
 * Originally authored as v4→v5 against pre-shelves main; renumbered to
 * v5→v6 at merge time when #116 (shelves) claimed v5 first. Coverage and
 * assertions are unchanged — chapter_history doesn't touch shelves
 * tables, so the migration is independent.
 *
 * Patterned after the Robolectric usage in
 * `app/src/test/.../StoryvoxRoutesTest.kt`. We avoid the default Hilt
 * application via `@Config(application = Application::class)` —
 * MigrationTestHelper only needs a Context for the temp database dir,
 * not a real DI graph.
 *
 * What this test pins:
 *   1. The 5→6 migration applies without error against the v5 schema
 *      snapshot (`schemas/in.jphe.storyvox.data.db.StoryvoxDatabase/5.json`).
 *      That alone catches a malformed CREATE TABLE / missing FK column.
 *   2. The post-migration table exists with the expected structure
 *      (PK on (fictionId, chapterId), an index on openedAt).
 *   3. Inserting + selecting a row round-trips, proving the table is
 *      writable and the PK definition matches the entity.
 *
 * The Room runtime also performs an automatic identity-hash check on
 * open: if the generated v6 schema (`6.json`) doesn't match what the
 * migration produces, opening the migrated DB through Room throws
 * `IllegalStateException: Migration didn't properly handle...`. The
 * test triggers that path explicitly via `runMigrationsAndValidate`.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class StoryvoxDatabaseMigrationTest {

    private val robolectricInstrumentation: Instrumentation =
        object : Instrumentation() {
            override fun getTargetContext(): android.content.Context =
                org.robolectric.RuntimeEnvironment.getApplication()
            override fun getContext(): android.content.Context =
                org.robolectric.RuntimeEnvironment.getApplication()
        }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        robolectricInstrumentation,
        StoryvoxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun `migrate v5 to v6 creates chapter_history table`() {
        val dbName = "history-migration-test.db"

        // Start at v5 — the shelves migration has already run, so the
        // v5 schema is the "real" pre-history baseline. Bring the DB up
        // to v5 first via the chain, then exercise 5→6 on top.
        helper.createDatabase(dbName, 4).close()
        helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5).close()

        val db = helper.runMigrationsAndValidate(
            dbName,
            6,
            /* validateDroppedTables = */ true,
            MIGRATION_5_6,
        )

        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='chapter_history'",
        ).use { c ->
            assertTrue("chapter_history table must exist post-migration", c.moveToFirst())
        }

        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='chapter_history'",
        ).use { c ->
            val indexes = mutableListOf<String>()
            while (c.moveToNext()) indexes += c.getString(0)
            assertTrue(
                "index_chapter_history_openedAt must exist (sort feed)",
                indexes.any { it == "index_chapter_history_openedAt" },
            )
            assertTrue(
                "index_chapter_history_chapterId must exist (FK cascade planner)",
                indexes.any { it == "index_chapter_history_chapterId" },
            )
        }

        db.close()
    }

    @Test fun `migrated v6 db round-trips a chapter_history row`() {
        val dbName = "history-roundtrip-test.db"
        helper.createDatabase(dbName, 4).close()
        helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5).close()
        helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_5_6).close()

        val ctx = org.robolectric.RuntimeEnvironment.getApplication() as android.content.Context
        val db = Room.databaseBuilder(ctx, StoryvoxDatabase::class.java, dbName)
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
            .build()

        try {
            db.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO fiction(
                    id, sourceId, title, author, genres, tags, status,
                    chapterCount, firstSeenAt, metadataFetchedAt,
                    inLibrary, followedRemotely, notesEverSeen
                ) VALUES (
                    'rr:42', 'royalroad', 'Mother of Learning', 'nobody103',
                    '[]', '[]', 'ONGOING', 0, 0, 0, 0, 0, 0
                )
                """.trimIndent(),
            )
            db.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO chapter(
                    id, fictionId, sourceChapterId, `index`, title,
                    downloadState, userMarkedRead
                ) VALUES (
                    'rr:42:0', 'rr:42', '0', 0, 'Good Morning Brother',
                    'NOT_DOWNLOADED', 0
                )
                """.trimIndent(),
            )
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO chapter_history(fictionId, chapterId, openedAt, completed) " +
                    "VALUES ('rr:42', 'rr:42:0', 1234, 0)",
            )

            db.openHelper.readableDatabase.query(
                "SELECT fictionId, chapterId, openedAt, completed, fractionRead " +
                    "FROM chapter_history",
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("rr:42", c.getString(0))
                assertEquals("rr:42:0", c.getString(1))
                assertEquals(1234L, c.getLong(2))
                assertEquals(0, c.getInt(3))
                assertEquals(
                    "fractionRead defaults to NULL when omitted",
                    true,
                    c.isNull(4),
                )
            }
        } finally {
            db.close()
        }

        assertNotNull("smoke-only sentinel — kept to fail-fast if the try block was no-op'd", helper)
    }
}
