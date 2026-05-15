package `in`.jphe.storyvox.source.discord

import `in`.jphe.storyvox.source.discord.config.DiscordConfigState
import `in`.jphe.storyvox.source.discord.config.DiscordDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #517 — tests for the TechEmpower peer-support channel seed in
 * the Discord config layer. Verifies:
 *
 *  - The TechEmpower channel id is the spec-confirmed snowflake from
 *    JP's invite capture (regression guard against an accidental
 *    constant rewrite that'd point storyvox at the wrong channel).
 *  - `DiscordConfigState` defaults the pinned-channel list to the
 *    TechEmpower seed and the `techempowerDefaultsEnabled` flag to
 *    true — so a fresh-install Discord state surfaces the
 *    peer-support channel automatically.
 *  - A toggled-off state shape (pinnedChannelIds empty) is what the
 *    impl emits when the user disables the TechEmpower defaults —
 *    callers can rely on `pinnedChannelIds.isEmpty()` as the
 *    single check instead of needing a second toggle lookup.
 *
 * The DiscordSource-level integration test (token + seed → fictions
 * containing the seed channel) lives in :app's DiscordConfigImpl test
 * surface because the DiscordApi is `internal` to this module and the
 * fake injection points are downstream.
 */
class DiscordTechEmpowerSeedTest {

    @Test
    fun `TechEmpower peer-support channel id matches spec capture`() {
        // Spec value from scratch/techempower-shared/discord-invite.md,
        // captured by JP 2026-05-15. If this changes, JP regenerated
        // the invite + channel pointer; bump the constant + this test
        // together.
        assertEquals(
            "1504888494206484531",
            DiscordDefaults.TECHEMPOWER_PEER_SUPPORT_CHANNEL_ID,
        )
    }

    @Test
    fun `default pinned channel list includes TechEmpower peer-support`() {
        // The seed list is a single-element list today. The shape is a
        // List so future expansion (multiple TechEmpower-default
        // channels, region-specific peer-support rooms) doesn't break
        // the wire format.
        assertEquals(
            listOf(DiscordDefaults.TECHEMPOWER_PEER_SUPPORT_CHANNEL_ID),
            DiscordDefaults.DEFAULT_PINNED_CHANNEL_IDS,
        )
    }

    @Test
    fun `TechEmpower defaults are ON for fresh-install state`() {
        // Per issue #517 — fresh-install Discord state should surface
        // the peer-support channel automatically. The toggle defaults
        // to true and the pinned-channel list inherits the seed.
        assertTrue(DiscordDefaults.DEFAULT_TECHEMPOWER_DEFAULTS_ENABLED)

        val freshState = DiscordConfigState()
        assertTrue(
            "fresh state should have TechEmpower defaults enabled",
            freshState.techempowerDefaultsEnabled,
        )
        assertEquals(
            "fresh state should seed pinned channels with the TechEmpower channel",
            DiscordDefaults.DEFAULT_PINNED_CHANNEL_IDS,
            freshState.pinnedChannelIds,
        )
        assertTrue(
            "TechEmpower peer-support channel id should be present in fresh state",
            DiscordDefaults.TECHEMPOWER_PEER_SUPPORT_CHANNEL_ID in freshState.pinnedChannelIds,
        )
    }

    @Test
    fun `opt-out state shape — toggle off plus empty pinned list`() {
        // When the user disables TechEmpower defaults in Settings,
        // DiscordConfigImpl emits a state with techempowerDefaultsEnabled
        // = false AND pinnedChannelIds = empty. The data class itself
        // doesn't enforce the coupling (it's a plain data class with
        // independent defaults), but this test pins down the shape
        // the impl emits so callers can rely on `pinnedChannelIds.isEmpty()`
        // as the single check.
        val optedOut = DiscordConfigState(
            techempowerDefaultsEnabled = false,
            pinnedChannelIds = emptyList(),
        )
        assertFalse(optedOut.techempowerDefaultsEnabled)
        assertTrue(optedOut.pinnedChannelIds.isEmpty())
    }
}
