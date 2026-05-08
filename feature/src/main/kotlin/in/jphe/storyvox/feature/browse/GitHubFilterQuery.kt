package `in`.jphe.storyvox.feature.browse

import `in`.jphe.storyvox.feature.api.GitHubPushedSince
import `in`.jphe.storyvox.feature.api.GitHubSearchFilter
import `in`.jphe.storyvox.feature.api.GitHubSort
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
 * Spec lines 199-211. Today's lands: minStars, language, pushedSince,
 * sort. Tag-multi-select and status are deferred to a follow-up; they
 * map onto GitHub's `topic:` and `archived:` qualifiers when added.
 *
 * Format examples:
 *  - empty filter, term="archmage" → `"archmage"`
 *  - empty term, minStars=10, language="en" → `"stars:>=10 language:en"`
 *  - term="dragon", pushedSince=Last30Days, sort=Stars (today=2026-05-08) →
 *    `"dragon pushed:>=2026-04-08 sort:stars"`
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
        sort != GitHubSort.BestMatch

fun GitHubSearchFilter.activeCount(): Int {
    var n = 0
    if (minStars != null && minStars > 0) n++
    if (!language.isNullOrBlank()) n++
    if (pushedSince != GitHubPushedSince.Any) n++
    if (sort != GitHubSort.BestMatch) n++
    return n
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
