package `in`.jphe.storyvox.data.db.entity

/**
 * Issue #116 — predefined library shelves. JP's decision (recorded on the
 * issue): three fixed built-in shelves; user-defined custom shelves are
 * explicitly out of scope for v1.
 *
 * A fiction may belong to multiple shelves simultaneously (e.g. Reading AND
 * Wishlist) — many-to-many membership via [FictionShelf]. The enum is the
 * source of truth; the junction row stores the enum name as a string so
 * adding a new value later (e.g. Favourites) requires no schema migration.
 *
 * **Display labels are deliberately in this file, not the UI module.** The
 * three names are the user-facing strings ("Reading" / "Read" / "Wishlist")
 * and that mapping is part of the data contract — every surface that
 * mentions a shelf shows the same word. If we ever localize, this is the
 * single attachment point.
 */
enum class Shelf(val displayName: String) {
    Reading("Reading"),
    Read("Read"),
    Wishlist("Wishlist"),
    ;

    companion object {
        /** Stable iteration order for chip rows and the manage-shelves sheet. */
        val ALL: List<Shelf> = listOf(Reading, Read, Wishlist)

        /**
         * Decode the persisted string back to a [Shelf]. Returns null for an
         * unknown name — callers either skip the row or surface a "stale
         * schema" warning. We don't throw because the FK + named-enum design
         * means an unknown value can only appear if someone hand-edited the
         * DB; refusing to crash on that is the lighter blast radius.
         */
        fun fromName(name: String): Shelf? = entries.firstOrNull { it.name == name }
    }
}
