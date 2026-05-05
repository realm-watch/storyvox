package `in`.jphe.storyvox.source.royalroad.model

internal object RoyalRoadIds {
    const val SOURCE_ID = "royalroad"
    const val SOURCE_NAME = "Royal Road"
    const val BASE_URL = "https://www.royalroad.com"
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    const val MIN_REQUEST_INTERVAL_MS = 1_000L
}

internal fun fictionUrl(fictionId: String): String =
    "${RoyalRoadIds.BASE_URL}/fiction/$fictionId"

internal fun chapterUrl(fictionId: String, chapterId: String, slug: String? = null, chapterSlug: String? = null): String {
    val base = "${RoyalRoadIds.BASE_URL}/fiction/$fictionId"
    val withSlug = if (slug != null) "$base/$slug" else base
    return if (chapterSlug != null) "$withSlug/chapter/$chapterId/$chapterSlug"
    else "$withSlug/chapter/$chapterId"
}

internal fun browseUrl(path: String, page: Int): String {
    val sep = if ("?" in path) "&" else "?"
    return "${RoyalRoadIds.BASE_URL}$path${if (page > 1) "${sep}page=$page" else ""}"
}

internal fun extractFictionIdFromHref(href: String): String? {
    val m = Regex("""/fiction/(\d+)""").find(href) ?: return null
    return m.groupValues[1]
}

internal fun extractChapterIdFromHref(href: String): String? {
    val m = Regex("""/chapter/(\d+)""").find(href) ?: return null
    return m.groupValues[1]
}
