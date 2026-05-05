package `in`.jphe.storyvox.data.db.migration

import androidx.room.migration.Migration

/**
 * v1 ships with no migrations. When the schema bumps, declare each step here
 * and pass them via `Room.databaseBuilder(...).addMigrations(...)` in
 * `DataModule.provideDb`.
 *
 * Schemas are exported to `core-data/schemas/` (see KSP `room.schemaLocation`)
 * so diffs land in PRs.
 */
val ALL_MIGRATIONS: Array<Migration> = emptyArray()
