package `in`.jphe.storyvox.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 ships with no migrations. When the schema bumps, declare each step here
 * and pass them via `Room.databaseBuilder(...).addMigrations(...)` in
 * `DataModule.provideDb`.
 *
 * Schemas are exported to `core-data/schemas/` (see KSP `room.schemaLocation`)
 * so diffs land in PRs.
 */

/**
 * v2 — adds `fiction.lastSeenRevision` for the GitHub-source commit-SHA
 * cheap-poll path (step 9 in the GitHub-source spec). Existing rows get
 * NULL so the first poll after upgrade still hits the full detail fetch
 * and persists a SHA, then subsequent polls take the short-circuit.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE fiction ADD COLUMN lastSeenRevision TEXT")
    }
}

/**
 * v3 — adds the multi-session AI tables (#81 AI integration). Pure
 * additive: two new tables and one index, no existing data touched.
 *
 * Schema mirrors the entity definitions in
 * `in.jphe.storyvox.data.db.entity.LlmSession` and
 * `in.jphe.storyvox.data.db.entity.LlmStoredMessage`. The `provider`
 * + `featureKind` columns are TEXT (storing enum names) for
 * forward-compatibility — adding a new enum value doesn't require a
 * migration.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `llm_session` (
                `id` TEXT NOT NULL PRIMARY KEY,
                `name` TEXT NOT NULL,
                `provider` TEXT NOT NULL,
                `model` TEXT NOT NULL,
                `systemPrompt` TEXT,
                `createdAt` INTEGER NOT NULL,
                `lastUsedAt` INTEGER NOT NULL,
                `featureKind` TEXT,
                `anchorFictionId` TEXT,
                `anchorChapterId` TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `llm_message` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `role` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                FOREIGN KEY(`sessionId`) REFERENCES `llm_session`(`id`)
                  ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_llm_message_sessionId` " +
                "ON `llm_message` (`sessionId`)",
        )
    }
}

/**
 * v4 — issue #121: per-chapter bookmark. One nullable column on the
 * chapter table; null on existing rows is the "no bookmark" sentinel
 * the entity already understands. Pure additive, no data backfill.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chapter ADD COLUMN bookmarkCharOffset INTEGER")
    }
}

/**
 * v5 — issue #116: predefined library shelves (Reading / Read /
 * Wishlist) with many-to-many membership. New junction table only; no
 * existing data touched, no backfill needed (existing library rows
 * simply have zero shelf memberships until the user pins them).
 *
 * `shelf` is TEXT to match the enum-name-as-string pattern from
 * [MIGRATION_2_3] — adding a fourth shelf value in v2 requires no
 * further migration. Index name matches Room's default
 * `index_<table>_<col>` convention so the schema diff stays clean.
 */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `fiction_shelf` (
                `fictionId` TEXT NOT NULL,
                `shelf` TEXT NOT NULL,
                `addedAt` INTEGER NOT NULL,
                PRIMARY KEY(`fictionId`, `shelf`),
                FOREIGN KEY(`fictionId`) REFERENCES `fiction`(`id`)
                  ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_fiction_shelf_shelf` " +
                "ON `fiction_shelf` (`shelf`)",
        )
    }
}

/**
 * v6 — issue #158: chapter_history table backing the Library "History"
 * sub-tab. Single CREATE TABLE + two indexes (openedAt for the ordering
 * the History feed uses, chapterId for the FK cascade-delete planner).
 * Purely additive — no existing data touched.
 *
 * The composite PK + UPSERT semantics mean re-opening the same chapter
 * twenty times produces one row, not twenty audit entries — see the
 * kdoc on [`in`.jphe.storyvox.data.db.entity.ChapterHistory] for the
 * product call.
 *
 * Originally authored as MIGRATION_4_5 against pre-shelves main;
 * renumbered to 5→6 at merge time because #116 (shelves) landed first
 * and claimed the v5 slot. SQL is unchanged — chapter_history doesn't
 * touch any shelves columns, so ordering is independent.
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chapter_history` (
                `fictionId` TEXT NOT NULL,
                `chapterId` TEXT NOT NULL,
                `openedAt` INTEGER NOT NULL,
                `completed` INTEGER NOT NULL DEFAULT 0,
                `fractionRead` REAL,
                PRIMARY KEY(`fictionId`, `chapterId`),
                FOREIGN KEY(`fictionId`) REFERENCES `fiction`(`id`)
                  ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`chapterId`) REFERENCES `chapter`(`id`)
                  ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chapter_history_openedAt` " +
                "ON `chapter_history` (`openedAt`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chapter_history_chapterId` " +
                "ON `chapter_history` (`chapterId`)",
        )
    }
}

/**
 * v7 — issues #373 + #374: audio-stream backend category. Adds
 * `audioUrl` column to the chapter table so chapters returned by
 * audio-source backends (KVMR community radio, future LibriVox /
 * Internet Archive) can carry a Media3-routable URL through the same
 * download/persist pipeline that text chapters use.
 *
 * Purely additive — existing chapter rows keep their `htmlBody` /
 * `plainBody` and the new column defaults to NULL, preserving the
 * TTS pipeline for every backend that doesn't surface audio. The
 * playback layer reads `audioUrl IS NOT NULL` to decide whether to
 * route through TTS or hand the URL to a Media3 ExoPlayer instance.
 */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chapter ADD COLUMN audioUrl TEXT")
    }
}

val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
)
