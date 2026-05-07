package `in`.jphe.storyvox.source.github.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of `GET /repos/{owner}/{repo}`. Only fields the source layer
 * actually consumes; the rest are tolerated via `ignoreUnknownKeys` on
 * the shared [GitHubJson] instance.
 *
 * https://docs.github.com/en/rest/repos/repos#get-a-repository
 */
@Serializable
internal data class GhRepo(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("description") val description: String? = null,
    @SerialName("default_branch") val defaultBranch: String,
    @SerialName("owner") val owner: GhOwner,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("language") val language: String? = null,
    @SerialName("topics") val topics: List<String> = emptyList(),
    @SerialName("stargazers_count") val stars: Int = 0,
    @SerialName("pushed_at") val pushedAt: String? = null,
    @SerialName("archived") val archived: Boolean = false,
)

@Serializable
internal data class GhOwner(
    @SerialName("login") val login: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/**
 * `GET /repos/{owner}/{repo}/contents/{path}`. The endpoint returns
 * either a single object (for files) or an array (for directories) —
 * step-3a only needs the file shape for fetching `book.toml` /
 * `storyvox.json` later.
 *
 * https://docs.github.com/en/rest/repos/contents#get-repository-content
 */
@Serializable
internal data class GhContent(
    @SerialName("name") val name: String,
    @SerialName("path") val path: String,
    @SerialName("sha") val sha: String,
    @SerialName("size") val size: Long,
    @SerialName("type") val type: String, // "file" | "dir" | "symlink" | "submodule"
    /** Base64-encoded body when type=="file" and `encoding`=="base64". */
    @SerialName("content") val content: String? = null,
    @SerialName("encoding") val encoding: String? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

/**
 * `GET /repos/{owner}/{repo}/compare/{base}...{head}`. Used for
 * commit-SHA-based new-chapter detection: the source stores the
 * last-seen commit, polls compare with that as base, and walks the
 * `commits` list for new chapter files.
 *
 * https://docs.github.com/en/rest/commits/commits#compare-two-commits
 */
@Serializable
internal data class GhCompareResponse(
    @SerialName("status") val status: String, // "identical" | "behind" | "ahead" | "diverged"
    @SerialName("ahead_by") val aheadBy: Int = 0,
    @SerialName("behind_by") val behindBy: Int = 0,
    @SerialName("total_commits") val totalCommits: Int = 0,
    @SerialName("commits") val commits: List<GhCommit> = emptyList(),
    @SerialName("files") val files: List<GhCommitFile> = emptyList(),
)

@Serializable
internal data class GhCommit(
    @SerialName("sha") val sha: String,
    @SerialName("commit") val commit: GhCommitDetail,
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
internal data class GhCommitDetail(
    @SerialName("message") val message: String,
    @SerialName("author") val author: GhCommitAuthor? = null,
)

@Serializable
internal data class GhCommitAuthor(
    @SerialName("name") val name: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("date") val date: String? = null,
)

@Serializable
internal data class GhCommitFile(
    @SerialName("filename") val filename: String,
    @SerialName("status") val status: String, // "added" | "removed" | "modified" | "renamed" | "copied" | "changed" | "unchanged"
    @SerialName("additions") val additions: Int = 0,
    @SerialName("deletions") val deletions: Int = 0,
    @SerialName("changes") val changes: Int = 0,
    @SerialName("sha") val sha: String? = null,
    @SerialName("previous_filename") val previousFilename: String? = null,
)
