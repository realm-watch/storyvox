package `in`.jphe.storyvox.source.ao3

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage

/**
 * Auth-gated AO3 listings exposed across the module boundary (#426 PR2).
 *
 * Mirrors [`GitHubAuthedSource`][in.jphe.storyvox.source.github.GitHubAuthedSource]
 * — the base [`in`.jphe.storyvox.data.source.FictionSource] surface
 * covers source-agnostic concerns (Popular / NewReleases / Search /
 * Follows). The two AO3-specific authed surfaces — work subscriptions
 * and Marked-for-Later — don't fit cleanly into Follows alone
 * (subscriptions is the closest analogue and routes through
 * `followsList`, but Marked-for-Later is a *separate* AO3 surface
 * with the same shape, and shoehorning it into Follows would conflate
 * two semantically different lists). Exposing them through this
 * interface lets the app-module Browse adapter wire each chip-strip
 * tab to the right endpoint without depending on `Ao3Source`'s
 * internal implementation type.
 *
 * Hilt binds [Ao3Source] to this interface in
 * [`in`.jphe.storyvox.source.ao3.di.Ao3Bindings].
 */
interface Ao3AuthedSource {

    /**
     * `/users/<username>/subscriptions?page=N` — one page of works
     * the signed-in user has subscribed to. Username is read from
     * the captured `auth_cookie.userId` column for the AO3 source;
     * returns [FictionResult.AuthRequired] when no session is
     * captured. The Browse → AO3 → My Subscriptions chip routes
     * here.
     *
     * Same shape as the [`FictionSource.followsList`][in.jphe.storyvox.data.source.FictionSource.followsList]
     * surface (which currently aliases this same endpoint), but
     * exposed here so the app-module Browse adapter can route to
     * the AO3 authed surface explicitly without depending on
     * `Ao3Source`'s internal implementation type.
     */
    suspend fun subscriptions(page: Int): FictionResult<ListPage<FictionSummary>>

    /**
     * `/users/<username>/readings?show=marked&page=N` — one page of
     * works the user has tagged with AO3's "Mark for Later" eye-
     * icon affordance. Requires the user to have "History" enabled
     * in their AO3 preferences; renders empty otherwise.
     */
    suspend fun markedForLater(page: Int): FictionResult<ListPage<FictionSummary>>
}
