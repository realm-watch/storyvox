package `in`.jphe.storyvox.feature.browse

import `in`.jphe.storyvox.feature.api.GitHubArchivedStatus
import `in`.jphe.storyvox.feature.api.GitHubPushedSince
import `in`.jphe.storyvox.feature.api.GitHubSearchFilter
import `in`.jphe.storyvox.feature.api.GitHubSort
import `in`.jphe.storyvox.feature.api.GitHubVisibilityFilter
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Compose a [GitHubSearchFilter] + free-text term into the qualifier-
 * laden string GitHub's `/search/repositories?q=...` consumes.
 *
 * The `topic:fiction OR topic:fanfiction OR topic:webnovel` prefix
 * that pins results to fiction-shaped repos is added by
 * `GitHubSource.search()` lower in the stack — this function only
 * produces the *user-controllable* part of the query (term + filter
 * qualifiers). `GitHubSource.search()` skips its own topic prefix
 * when this layer has already added one (see the `topic:` check
 * there); we don't add a topic here unless the user asks for it.
 *
 * Spec lines 199-211. Lands today: minStars, language, pushedSince,
 * sort, tags (#205), archivedStatus (#205).
 *
 * Format examples:
 *  - empty filter, term="archmage" → `"archmage"`
 *  - empty term, minStars=10, language="en" → `"stars:>=10 language:en"`
 *  - term="dragon", pushedSince=Last30Days, sort=Stars (today=2026-05-08) →
 *    `"dragon pushed:>=2026-04-08 sort:stars"`
 *  - tags={litrpg, fantasy}, archivedStatus=ActiveOnly →
 *    `"topic:litrpg topic:fantasy archived:false"`
 */
fun composeGitHubQuery(
    term: String,
    filter: GitHubSearchFilter,
    today: LocalDate = LocalDate.now(),
): String = buildString {
    val cleanTerm = term.trim()
    if (cleanTerm.isNotEmpty()) append(cleanTerm)

    filter.minStars?.let { stars ->
        if (stars > 0) {
            if (isNotEmpty()) append(' ')
            append("stars:>=").append(stars)
        }
    }

    filter.language?.takeIf { it.isNotBlank() }?.let { lang ->
        if (isNotEmpty()) append(' ')
        append("language:").append(lang.lowercase())
    }

    filter.pushedSince.toCutoffDate(today)?.let { cutoff ->
        if (isNotEmpty()) append(' ')
        append("pushed:>=").append(ISO_DATE.format(cutoff))
    }

    // Tags as repeated topic: qualifiers — GitHub ANDs them. Sanitized:
    // lowercase + strip whitespace + drop blanks. The fiction-prefix
    // 'topic:fiction OR topic:fanfiction OR topic:webnovel' added in
    // GitHubSource.search() is suppressed when this layer has already
    // added a topic, so user-selected tags fully replace the prefix.
    filter.tags.forEach { tag ->
        val clean = tag.trim().lowercase()
        if (clean.isEmpty()) return@forEach
        if (isNotEmpty()) append(' ')
        append("topic:").append(clean)
    }

    filter.archivedStatus.toQualifier()?.let { archQ ->
        if (isNotEmpty()) append(' ')
        append(archQ)
    }

    filter.visibility.toQualifier()?.let { visQ ->
        if (isNotEmpty()) append(' ')
        append(visQ)
    }

    filter.sort.toQualifier()?.let { sortQ ->
        if (isNotEmpty()) append(' ')
        append(sortQ)
    }
}

/** True when [filter] has any non-default knob set. Drives the
 *  filter-button badge on Browse → GitHub. */
fun GitHubSearchFilter.isActive(): Boolean =
    (minStars != null && minStars > 0) ||
        !language.isNullOrBlank() ||
        pushedSince != GitHubPushedSince.Any ||
        sort != GitHubSort.BestMatch ||
        tags.isNotEmpty() ||
        archivedStatus != GitHubArchivedStatus.Any ||
        visibility != GitHubVisibilityFilter.Both

fun GitHubSearchFilter.activeCount(): Int {
    var n = 0
    if (minStars != null && minStars > 0) n++
    if (!language.isNullOrBlank()) n++
    if (pushedSince != GitHubPushedSince.Any) n++
    if (sort != GitHubSort.BestMatch) n++
    if (tags.isNotEmpty()) n++
    if (archivedStatus != GitHubArchivedStatus.Any) n++
    if (visibility != GitHubVisibilityFilter.Both) n++
    return n
}

private fun GitHubArchivedStatus.toQualifier(): String? = when (this) {
    GitHubArchivedStatus.Any -> null
    GitHubArchivedStatus.ActiveOnly -> "archived:false"
    GitHubArchivedStatus.ArchivedOnly -> "archived:true"
}

private fun GitHubVisibilityFilter.toQualifier(): String? = when (this) {
    GitHubVisibilityFilter.Both -> null
    GitHubVisibilityFilter.PublicOnly -> "is:public"
    GitHubVisibilityFilter.PrivateOnly -> "is:private"
}

private fun GitHubPushedSince.toCutoffDate(today: LocalDate): LocalDate? = when (this) {
    GitHubPushedSince.Any -> null
    GitHubPushedSince.Last7Days -> today.minusDays(7)
    GitHubPushedSince.Last30Days -> today.minusDays(30)
    GitHubPushedSince.Last90Days -> today.minusDays(90)
    GitHubPushedSince.LastYear -> today.minusYears(1)
}

private fun GitHubSort.toQualifier(): String? = when (this) {
    GitHubSort.BestMatch -> null // API default — no qualifier needed
    GitHubSort.Stars -> "sort:stars"
    GitHubSort.Updated -> "sort:updated"
}

private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
