package `in`.jphe.storyvox.source.github

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.github.net.GitHubApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stub [FictionSource] for the GitHub source. The module is built and
 * test-covered in step-3a (this PR), but **not bound in Hilt yet** —
 * that wires up in step 3d when manifest parsing actually returns real
 * [FictionDetail] data. Today every call raises [NotImplementedError]
 * with a pointer to the implementing step in the spec, so any
 * accidental binding fails loudly during dev rather than silently
 * returning empty pages.
 *
 * The constructor accepts [GitHubApi] so the dependency graph is in
 * place — when the Hilt binding lands, no plumbing change here.
 */
@Singleton
@Suppress("unused", "UNUSED_PARAMETER") // intentional stub — see kdoc
internal class GitHubSource @Inject constructor(
    private val api: GitHubApi,
) : FictionSource {

    override val id: String = "github"
    override val displayName: String = "GitHub"

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        throw NotImplementedError(STEP_3C_BROWSE)

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        throw NotImplementedError(STEP_3C_BROWSE)

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        throw NotImplementedError(STEP_3C_BROWSE)

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> =
        throw NotImplementedError(STEP_3C_BROWSE)

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> =
        throw NotImplementedError(STEP_3D_DETAIL)

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> =
        throw NotImplementedError(STEP_3D_DETAIL)

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        throw NotImplementedError(STEP_3F_AUTH)

    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> =
        throw NotImplementedError(STEP_3F_AUTH)

    override suspend fun genres(): FictionResult<List<String>> =
        throw NotImplementedError(STEP_3C_BROWSE)

    private companion object {
        // References the build sequence in
        // docs/superpowers/specs/2026-05-06-github-source-design.md
        // (lines 256-262). Every NotImplementedError points at the
        // step that fills it in.
        const val STEP_3C_BROWSE = "GitHub source browse not implemented yet — lands in step 3c (registry-only Featured row + search)"
        const val STEP_3D_DETAIL = "GitHub source detail/chapter not implemented yet — lands in step 3d (manifest parsing + markdown rendering)"
        const val STEP_3F_AUTH = "GitHub source auth-gated calls not implemented yet — lands in step 3f (optional PAT support)"
    }
}
