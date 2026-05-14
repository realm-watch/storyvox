package `in`.jphe.storyvox.llm.tools

import kotlinx.serialization.json.JsonObject

/**
 * Issue #216 — the executable side of a [ToolSpec]. Lives in
 * `:core-llm` as a pure interface; concrete implementations live in
 * `:feature/chat` where they can talk to ShelfRepository,
 * ChapterRepository, PlaybackControllerUi, etc.
 *
 * The handler is a single suspending function so it can hop a
 * Dispatcher without making the caller pick one. The receiving
 * provider runs it under the same coroutine scope that drives the
 * stream, so cancelling the chat send aborts in-flight tool calls
 * too.
 */
fun interface ToolHandler {
    /** Run the tool against the given parsed [arguments]. Implementations
     *  should:
     *   1. Pull typed values out of [arguments]; return Error on
     *      missing/wrong-typed args.
     *   2. Validate domain constraints (e.g. shelf name is one of
     *      Reading / Read / Wishlist); return Error on rejection.
     *   3. Execute against the storyvox repo/controller layer;
     *      return Success with a short natural-language summary.
     *
     *  Don't throw — wrap any expected failure mode as
     *  [ToolResult.Error]. Unhandled throws bubble up and abort the
     *  whole chat send, which is rarely what the user wants. */
    suspend fun execute(arguments: JsonObject): ToolResult
}

/**
 * Registry binding tool name → handler. Built once at app startup
 * (via Hilt) and consumed by the chat layer. A [ToolCatalog] is
 * derived from the same map by mapping `name → ToolSpec`, so the
 * advertised tools and the executable tools are guaranteed to stay
 * in lockstep.
 */
class ToolRegistry(
    private val entries: Map<String, Pair<ToolSpec, ToolHandler>>,
) {
    /** All registered tool specs, in registration order. */
    val catalog: List<ToolSpec> = entries.values.map { it.first }

    /** Look up a handler by name; null when the model called a
     *  function that isn't registered (shouldn't happen — the model
     *  only sees [catalog] — but possible on bad caching). */
    fun handler(name: String): ToolHandler? = entries[name]?.second

    companion object {
        /** Convenience builder. Pass `name to (spec to handler)` pairs. */
        fun of(vararg pairs: Triple<String, ToolSpec, ToolHandler>): ToolRegistry =
            ToolRegistry(pairs.associate { it.first to (it.second to it.third) })

        /** Build from a [ToolSpec] list + lookup function. The lookup
         *  must return non-null for every spec — throws on missing. */
        fun build(
            specs: List<ToolSpec>,
            lookup: (String) -> ToolHandler,
        ): ToolRegistry = ToolRegistry(
            specs.associate { spec ->
                spec.name to (spec to lookup(spec.name))
            },
        )

        /** Empty registry — used when the user has disabled actions
         *  in Settings → AI. Providers see an empty catalog and skip
         *  the tools array in the request body. */
        val EMPTY: ToolRegistry = ToolRegistry(emptyMap())
    }
}
