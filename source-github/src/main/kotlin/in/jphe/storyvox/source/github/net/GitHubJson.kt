package `in`.jphe.storyvox.source.github.net

import kotlinx.serialization.json.Json

/**
 * Shared kotlinx.serialization Json instance for the GitHub source.
 * Tolerant config: GitHub adds fields over time and we shouldn't break
 * on unknown ones. Lenient unicode handling matches GitHub's UTF-8.
 */
internal val GitHubJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}
