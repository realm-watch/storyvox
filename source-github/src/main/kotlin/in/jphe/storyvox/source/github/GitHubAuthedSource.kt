package `in`.jphe.storyvox.source.github

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage

/**
 * Auth-gated GitHub listings exposed across the module boundary.
 *
 * The base [`in`.jphe.storyvox.data.source.FictionSource] surface is
 * source-agnostic; entries here are *GitHub-specific* and only meaningful
 * when the user has signed in via the OAuth Device Flow (#91). Each
 * implementation method internally requires the bearer token attached
 * by [`in`.jphe.storyvox.source.github.auth.GitHubAuthInterceptor]; an
 * anonymous caller gets a `FictionResult.NetworkError` (the 401 maps
 * through `mapResponse` → `HttpError(401, ...)` → `NetworkError`).
 *
 * Surfaced as a public interface so the app-module Browse adapter can
 * route `BrowseSource.GitHubMyRepos` to this surface without exposing
 * the package-internal [GitHubSource] implementation type. Hilt binds
 * [GitHubSource] to this interface in
 * [`in`.jphe.storyvox.source.github.di.GitHubBindings].
 */
interface GitHubAuthedSource {
    /**
     * `/user/repos?affiliation=owner,collaborator&sort=updated`. One
     * page of the signed-in user's repos. Each result maps to a
     * GitHub fiction id (`github:owner/repo`) consumable by
     * `GitHubSource.fictionDetail` — the Browse → My Repos row uses
     * this exactly like the Popular/NewReleases tabs use the curated
     * registry.
     */
    suspend fun myRepos(page: Int): FictionResult<ListPage<FictionSummary>>

    /**
     * `/user/starred?sort=updated`. One page of the signed-in user's
     * stars, filtered to fiction-shaped repos (`topic:fiction OR
     * topic:fanfiction OR topic:webnovel` — same set the public Browse
     * → GitHub uses). Drives the Browse → Starred tab (#201).
     */
    suspend fun starred(page: Int): FictionResult<ListPage<FictionSummary>>
}
