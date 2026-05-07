package `in`.jphe.storyvox.source.github.model

import `in`.jphe.storyvox.source.github.net.GitHubJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM deserialization tests for the GitHub REST response shapes
 * we'll consume in step-3 follow-ups. Fixture strings are abridged
 * versions of the official `developer.github.com` example responses,
 * trimmed to what storyvox actually reads.
 *
 * No real network — these run in seconds and verify the
 * `ignoreUnknownKeys = true` config is forgiving of GitHub's larger
 * payloads.
 */
class GitHubModelsTest {

    // ─── GhRepo ────────────────────────────────────────────────────────

    @Test fun `repo deserializes the fields we read`() {
        val json = """
            {
              "id": 1296269,
              "name": "Hello-World",
              "full_name": "octocat/Hello-World",
              "description": "This your first repo!",
              "default_branch": "main",
              "owner": { "login": "octocat", "avatar_url": "https://example.com/a.png" },
              "html_url": "https://github.com/octocat/Hello-World",
              "language": "Markdown",
              "topics": ["fiction", "fantasy"],
              "stargazers_count": 80,
              "pushed_at": "2026-04-29T12:00:00Z",
              "archived": false,
              "private": false,
              "fork": false,
              "url": "https://api.github.com/repos/octocat/Hello-World",
              "watchers_count": 80,
              "size": 108
            }
        """.trimIndent()

        val repo = GitHubJson.decodeFromString<GhRepo>(json)

        assertEquals(1296269L, repo.id)
        assertEquals("Hello-World", repo.name)
        assertEquals("octocat/Hello-World", repo.fullName)
        assertEquals("This your first repo!", repo.description)
        assertEquals("main", repo.defaultBranch)
        assertEquals("octocat", repo.owner.login)
        assertEquals("https://example.com/a.png", repo.owner.avatarUrl)
        assertEquals("https://github.com/octocat/Hello-World", repo.htmlUrl)
        assertEquals("Markdown", repo.language)
        assertEquals(listOf("fiction", "fantasy"), repo.topics)
        assertEquals(80, repo.stars)
        assertFalse(repo.archived)
    }

    @Test fun `repo tolerates missing optional fields`() {
        val json = """
            {
              "id": 1,
              "name": "minimal",
              "full_name": "owner/minimal",
              "default_branch": "main",
              "owner": { "login": "owner" },
              "html_url": "https://github.com/owner/minimal"
            }
        """.trimIndent()

        val repo = GitHubJson.decodeFromString<GhRepo>(json)

        assertNull(repo.description)
        assertNull(repo.language)
        assertEquals(emptyList<String>(), repo.topics)
        assertEquals(0, repo.stars)
        assertFalse(repo.archived)
        assertNull(repo.owner.avatarUrl)
    }

    @Test fun `repo ignores unknown keys`() {
        // GitHub adds new fields (organization, license, security_and_analysis,
        // etc.) over time. We must not break when they appear.
        val json = """
            {
              "id": 1, "name": "x", "full_name": "o/x",
              "default_branch": "main",
              "owner": { "login": "o" },
              "html_url": "https://github.com/o/x",
              "future_field_we_have_never_seen": "shrug",
              "license": { "key": "mit", "name": "MIT" }
            }
        """.trimIndent()

        val repo = GitHubJson.decodeFromString<GhRepo>(json)
        assertEquals("x", repo.name)
    }

    // ─── GhContent ─────────────────────────────────────────────────────

    @Test fun `content deserializes a base64-encoded file`() {
        val json = """
            {
              "name": "book.toml",
              "path": "book.toml",
              "sha": "3d21ec53a331a6f037a91c368710b99387d012c1",
              "size": 5362,
              "type": "file",
              "encoding": "base64",
              "content": "W2Jvb2tdCnRpdGxlID0gIkV4YW1wbGUiCg==",
              "download_url": "https://raw.githubusercontent.com/o/r/main/book.toml",
              "html_url": "https://github.com/o/r/blob/main/book.toml"
            }
        """.trimIndent()

        val content = GitHubJson.decodeFromString<GhContent>(json)

        assertEquals("book.toml", content.name)
        assertEquals("file", content.type)
        assertEquals("base64", content.encoding)
        assertNotNull(content.content)
        assertEquals(
            "https://raw.githubusercontent.com/o/r/main/book.toml",
            content.downloadUrl,
        )
    }

    @Test fun `content for a directory deserializes without content body`() {
        val json = """
            {
              "name": "src",
              "path": "src",
              "sha": "aabbccdd",
              "size": 0,
              "type": "dir"
            }
        """.trimIndent()

        val content = GitHubJson.decodeFromString<GhContent>(json)

        assertEquals("dir", content.type)
        assertNull(content.content)
        assertNull(content.encoding)
    }

    // ─── GhCompareResponse ─────────────────────────────────────────────

    @Test fun `compare deserializes ahead status with commits and changed files`() {
        val json = """
            {
              "url": "https://api.github.com/repos/o/r/compare/abc...def",
              "status": "ahead",
              "ahead_by": 2,
              "behind_by": 0,
              "total_commits": 2,
              "commits": [
                {
                  "sha": "1111111111111111111111111111111111111111",
                  "html_url": "https://github.com/o/r/commit/1111",
                  "commit": {
                    "message": "chapter 12: the brass gate",
                    "author": {
                      "name": "Author",
                      "email": "a@example.com",
                      "date": "2026-04-30T12:00:00Z"
                    }
                  }
                },
                {
                  "sha": "2222222222222222222222222222222222222222",
                  "commit": { "message": "fix typo" }
                }
              ],
              "files": [
                {
                  "sha": "ffff",
                  "filename": "chapters/12-brass-gate.md",
                  "status": "added",
                  "additions": 320,
                  "deletions": 0,
                  "changes": 320
                }
              ]
            }
        """.trimIndent()

        val cmp = GitHubJson.decodeFromString<GhCompareResponse>(json)

        assertEquals("ahead", cmp.status)
        assertEquals(2, cmp.aheadBy)
        assertEquals(0, cmp.behindBy)
        assertEquals(2, cmp.totalCommits)
        assertEquals(2, cmp.commits.size)
        assertEquals("chapter 12: the brass gate", cmp.commits[0].commit.message)
        assertEquals("Author", cmp.commits[0].commit.author?.name)
        assertNull(cmp.commits[1].commit.author)
        assertEquals(1, cmp.files.size)
        assertEquals("added", cmp.files[0].status)
        assertEquals("chapters/12-brass-gate.md", cmp.files[0].filename)
    }

    @Test fun `compare handles identical status with empty commits and files`() {
        val json = """
            {
              "status": "identical",
              "ahead_by": 0,
              "behind_by": 0,
              "total_commits": 0,
              "commits": [],
              "files": []
            }
        """.trimIndent()

        val cmp = GitHubJson.decodeFromString<GhCompareResponse>(json)

        assertEquals("identical", cmp.status)
        assertEquals(0, cmp.aheadBy)
        assertTrue(cmp.commits.isEmpty())
        assertTrue(cmp.files.isEmpty())
    }

    @Test fun `compare with omitted commits and files defaults to empty lists`() {
        // Some GitHub responses elide the lists when they're empty.
        // `coerceInputValues` + the default emptyList() on the model
        // means we shouldn't crash when the keys are simply absent.
        val json = """
            {
              "status": "identical",
              "ahead_by": 0,
              "behind_by": 0,
              "total_commits": 0
            }
        """.trimIndent()

        val cmp = GitHubJson.decodeFromString<GhCompareResponse>(json)

        assertTrue(cmp.commits.isEmpty())
        assertTrue(cmp.files.isEmpty())
    }
}
