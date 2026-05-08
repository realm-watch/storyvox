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

val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
