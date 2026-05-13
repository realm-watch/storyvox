package `in`.jphe.storyvox.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Issue #116 — junction table for the many-to-many shelf membership.
 *
 * One row = one fiction sits on one shelf. A fiction with two memberships
 * (e.g. Reading + Wishlist) has two rows here, keyed by (fictionId, shelf).
 *
 * `shelf` stores [Shelf.name] as a string. Doing this rather than persisting
 * the ordinal means we can add a fourth shelf in v2 (Favourites,
 * "Did-Not-Finish", ...) by appending a new enum value with no schema
 * migration — exactly the same pattern as `provider` / `featureKind` on
 * `llm_session` (see [Migrations.MIGRATION_2_3]).
 *
 * `ON DELETE CASCADE` on the fiction FK guarantees that purging a fiction
 * (e.g. via `FictionDao.deleteIfTransient`) also drops its shelf
 * memberships — leaving them orphaned would manifest as ghost entries on a
 * shelf with no card to render.
 */
@Entity(
    tableName = "fiction_shelf",
    primaryKeys = ["fictionId", "shelf"],
    foreignKeys = [
        ForeignKey(
            entity = Fiction::class,
            parentColumns = ["id"],
            childColumns = ["fictionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // Shelf-scoped read path (`observeByShelf`) needs an index on the
        // shelf column. The composite PK already covers fictionId-first
        // queries, so no second index there.
        Index(value = ["shelf"]),
    ],
)
data class FictionShelf(
    val fictionId: String,
    /** [Shelf.name] persisted as a string — see class kdoc. */
    val shelf: String,
    /** Wall-clock millis at the moment the user added the fiction to this
     *  shelf. Currently unused for ordering (shelves render in
     *  library-add order via the `fiction` table), but recorded now so a
     *  future "recently shelved" surface or sync timestamp doesn't need a
     *  schema bump. */
    val addedAt: Long,
)
