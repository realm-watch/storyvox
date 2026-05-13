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

val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
