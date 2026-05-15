package `in`.jphe.storyvox.source.slack

import `in`.jphe.storyvox.source.slack.net.SlackAuthTest
import `in`.jphe.storyvox.source.slack.net.SlackConversationsHistory
import `in`.jphe.storyvox.source.slack.net.SlackConversationsList
import `in`.jphe.storyvox.source.slack.net.SlackError
import `in`.jphe.storyvox.source.slack.net.SlackUserInfoResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #454 — JSON parsing checks for the Slack Web API responses
 * storyvox reads. These run against captured-from-docs fixtures,
 * not live Slack; they guarantee storyvox keeps parsing the
 * documented shapes even if Slack adds new fields (the
 * `ignoreUnknownKeys = true` posture).
 */
class SlackModelsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `parses auth test success response`() {
        // Real response shape from Slack's docs for auth.test on a
        // bot token. Carries the workspace name, workspace url,
        // bot user id, and bot display name we use for the Settings
        // confirmation card.
        val body = """
            {
              "ok": true,
              "url": "https://techempower.slack.com/",
              "team": "TechEmpower",
              "user": "storyvox_bot",
              "team_id": "T012345678",
              "user_id": "U012345678",
              "bot_id": "B012345678",
              "is_enterprise_install": false
            }
        """.trimIndent()

        val auth = json.decodeFromString<SlackAuthTest>(body)

        assertTrue(auth.ok)
        assertEquals("TechEmpower", auth.team)
        assertEquals("https://techempower.slack.com/", auth.url)
        assertEquals("storyvox_bot", auth.user)
        assertEquals("U012345678", auth.userId)
        assertEquals("T012345678", auth.teamId)
        assertNull(auth.error)
    }

    @Test
    fun `parses auth test failure response`() {
        // Real failure response — `ok=false` with an error code.
        // The transport layer maps `invalid_auth` to AuthRequired.
        val body = """
            {
              "ok": false,
              "error": "invalid_auth"
            }
        """.trimIndent()

        val auth = json.decodeFromString<SlackAuthTest>(body)
        val err = json.decodeFromString<SlackError>(body)

        assertFalse(auth.ok)
        assertEquals("invalid_auth", auth.error)
        assertFalse(err.ok)
        assertEquals("invalid_auth", err.error)
    }

    @Test
    fun `parses conversations list response with mixed channel types`() {
        // Public + private + archived channels in one response.
        // The source layer filters to `is_member=true`; this test
        // verifies the wire shape preserves the fields the filter
        // depends on.
        val body = """
            {
              "ok": true,
              "channels": [
                {
                  "id": "C0123ABCDEF",
                  "name": "general",
                  "is_channel": true,
                  "is_private": false,
                  "is_member": true,
                  "is_archived": false,
                  "created": 1500000000,
                  "topic": {
                    "value": "Workspace-wide chat",
                    "creator": "U0123",
                    "last_set": 1500000100
                  },
                  "purpose": {
                    "value": "Talk about anything",
                    "creator": "U0123",
                    "last_set": 1500000100
                  },
                  "num_members": 42
                },
                {
                  "id": "C0456PRIVATE",
                  "name": "secret-project",
                  "is_channel": false,
                  "is_private": true,
                  "is_member": true,
                  "topic": {"value": "", "creator": "", "last_set": 0},
                  "purpose": {"value": "Top secret stuff", "creator": "", "last_set": 0}
                },
                {
                  "id": "C0789NOTJOIN",
                  "name": "no-bot-here",
                  "is_channel": true,
                  "is_member": false
                },
                {
                  "id": "C0ABCARCHIV",
                  "name": "old-channel",
                  "is_channel": true,
                  "is_member": true,
                  "is_archived": true
                }
              ],
              "response_metadata": {
                "next_cursor": ""
              }
            }
        """.trimIndent()

        val list = json.decodeFromString<SlackConversationsList>(body)

        assertTrue(list.ok)
        assertEquals(4, list.channels.size)

        // Public channel with full metadata
        val general = list.channels[0]
        assertEquals("C0123ABCDEF", general.id)
        assertEquals("general", general.name)
        assertTrue(general.isMember)
        assertEquals("Workspace-wide chat", general.topic?.value)
        assertEquals("Talk about anything", general.purpose?.value)

        // Private channel — different is_channel/is_private flags
        val secret = list.channels[1]
        assertTrue(secret.isPrivate)
        assertEquals("", secret.topic?.value)

        // Non-member channel — filter target
        assertFalse(list.channels[2].isMember)

        // Archived channel
        assertTrue(list.channels[3].isArchived)

        // Empty cursor = no more pages
        assertEquals("", list.responseMetadata?.nextCursor)
    }

    @Test
    fun `parses conversations history with messages and attachments`() {
        // Mix of regular user message, message with file attachment,
        // and a system join message. The source layer filters the
        // join out via SYSTEM_SUBTYPES.
        val body = """
            {
              "ok": true,
              "messages": [
                {
                  "type": "message",
                  "ts": "1747340531.123456",
                  "user": "U012345",
                  "text": "Has anyone seen the dragon chapter?"
                },
                {
                  "type": "message",
                  "ts": "1747340500.123456",
                  "user": "U098765",
                  "text": "Posting the sketch here",
                  "files": [
                    {
                      "id": "F0123",
                      "name": "dragon-sketch.png",
                      "title": "Dragon sketch v3",
                      "mimetype": "image/png",
                      "size": 245678
                    }
                  ]
                },
                {
                  "type": "message",
                  "subtype": "channel_join",
                  "ts": "1747340000.000100",
                  "user": "U111111",
                  "text": "<@U111111> has joined the channel"
                },
                {
                  "type": "message",
                  "subtype": "bot_message",
                  "ts": "1747339900.000200",
                  "bot_id": "B012345",
                  "username": "GitHub",
                  "text": "PR #454 opened"
                }
              ],
              "has_more": true,
              "response_metadata": {
                "next_cursor": "bmV4dF90czoxNjQwMDAwMDAw"
              }
            }
        """.trimIndent()

        val history = json.decodeFromString<SlackConversationsHistory>(body)

        assertTrue(history.ok)
        assertEquals(4, history.messages.size)
        assertTrue(history.hasMore)
        assertEquals("bmV4dF90czoxNjQwMDAwMDAw", history.responseMetadata?.nextCursor)

        // User message — no subtype, has text
        val first = history.messages[0]
        assertEquals("1747340531.123456", first.ts)
        assertEquals("U012345", first.user)
        assertNull(first.subtype)
        assertTrue(first.isUserContentMessage())

        // File-bearing message
        val withFile = history.messages[1]
        assertEquals(1, withFile.files?.size)
        assertEquals("dragon-sketch.png", withFile.files?.first()?.name)
        assertEquals("Dragon sketch v3", withFile.files?.first()?.title)
        assertEquals(245678L, withFile.files?.first()?.size)

        // System join message — filtered out by the source
        val join = history.messages[2]
        assertEquals("channel_join", join.subtype)
        assertFalse(join.isUserContentMessage())

        // Bot message — kept
        val bot = history.messages[3]
        assertEquals("bot_message", bot.subtype)
        assertEquals("B012345", bot.botId)
        assertEquals("GitHub", bot.username)
        assertTrue(bot.isUserContentMessage())
    }

    @Test
    fun `parses users info response`() {
        val body = """
            {
              "ok": true,
              "user": {
                "id": "U012345",
                "name": "alice.dev",
                "real_name": "Alice Developer",
                "deleted": false,
                "is_bot": false,
                "profile": {
                  "display_name": "Alice",
                  "display_name_normalized": "alice",
                  "real_name": "Alice Developer",
                  "real_name_normalized": "alice developer"
                }
              }
            }
        """.trimIndent()

        val resp = json.decodeFromString<SlackUserInfoResponse>(body)

        assertTrue(resp.ok)
        assertNotNull(resp.user)
        assertEquals("U012345", resp.user?.id)
        assertEquals("Alice", resp.user?.profile?.displayName)
        assertEquals("Alice Developer", resp.user?.realName)
        assertFalse(resp.user?.isBot == true)
    }

    @Test
    fun `parses cursor pagination empty cursor as no more pages`() {
        // Two consecutive responses — first has a cursor, second has
        // empty cursor. The source's pagination loop terminates on
        // the empty cursor.
        val firstPage = """{"ok":true,"channels":[],"response_metadata":{"next_cursor":"abc123"}}"""
        val lastPage = """{"ok":true,"channels":[],"response_metadata":{"next_cursor":""}}"""

        val first = json.decodeFromString<SlackConversationsList>(firstPage)
        val last = json.decodeFromString<SlackConversationsList>(lastPage)

        assertEquals("abc123", first.responseMetadata?.nextCursor)
        assertEquals("", last.responseMetadata?.nextCursor)
    }

    @Test
    fun `parses error envelope with warning`() {
        // Slack sometimes returns ok=false alongside a warning text
        // (e.g. when a token is expiring soon). The source maps the
        // error code to the right FictionResult variant and surfaces
        // the warning in the human-readable message.
        val body = """
            {
              "ok": false,
              "error": "token_expired",
              "warning": "Token will expire in 24 hours"
            }
        """.trimIndent()

        val err = json.decodeFromString<SlackError>(body)
        assertFalse(err.ok)
        assertEquals("token_expired", err.error)
        assertEquals("Token will expire in 24 hours", err.warning)
    }
}
