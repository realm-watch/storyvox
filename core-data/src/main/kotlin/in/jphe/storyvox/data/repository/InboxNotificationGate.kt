package `in`.jphe.storyvox.data.repository

/**
 * Issue #383 — per-source mute gate for the Inbox event pipeline.
 *
 * Lives in `:core-data` so the workers and source plumbing can inject
 * it without depending on `:feature/api` (where the matching user
 * settings live). The production binding is in `:app/InboxNotificationGateImpl`
 * and reads from the same DataStore prefs that back the Settings
 * toggles. A default test fake can return [DefaultEnabled] for unit
 * tests that don't care about the gate.
 *
 * The check is intentionally synchronous-ish (a suspend `isEnabled`
 * call that just reads the latest DataStore snapshot). Pollers are
 * already on a background coroutine; the cost of the lookup is
 * negligible next to the network call we're about to skip.
 */
interface InboxNotificationGate {
    /**
     * True when the user wants Inbox events from [sourceId] to land in
     * the Inbox feed (and optionally fire a system notification).
     * Default-true when the source isn't recognised — never silently
     * drop a brand-new backend's events because we forgot to wire its
     * toggle.
     */
    suspend fun isEnabled(sourceId: String): Boolean

    companion object {
        /** Test fake: every source is enabled. */
        val DefaultEnabled: InboxNotificationGate = object : InboxNotificationGate {
            override suspend fun isEnabled(sourceId: String): Boolean = true
        }
    }
}
