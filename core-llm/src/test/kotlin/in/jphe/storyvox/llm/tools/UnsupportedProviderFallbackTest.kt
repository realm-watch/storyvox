package `in`.jphe.storyvox.llm.tools

import `in`.jphe.storyvox.llm.LlmMessage
import `in`.jphe.storyvox.llm.LlmProvider
import `in`.jphe.storyvox.llm.ProbeResult
import `in`.jphe.storyvox.llm.ProviderId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Issue #216 — providers that haven't opted into tool support inherit
 * the default [LlmProvider.chatWithTools], which falls through to
 * [LlmProvider.stream] and wraps each text emit as a
 * [ChatStreamEvent.TextDelta]. Tool calls are silently dropped — the
 * UI is responsible for the "actions not supported on this provider"
 * empty state.
 *
 * Test confirms: (a) [LlmProvider.supportsTools] defaults to false,
 * and (b) a tool-less call through the default path is a 1:1
 * passthrough.
 */
class UnsupportedProviderFallbackTest {

    private object FakeUnsupportedProvider : LlmProvider {
        override val id: ProviderId = ProviderId.Ollama
        override fun stream(
            messages: List<LlmMessage>,
            systemPrompt: String?,
            model: String?,
        ): kotlinx.coroutines.flow.Flow<String> = flowOf("hello ", "world")

        override suspend fun probe(): ProbeResult = ProbeResult.Ok
    }

    @Test
    fun `default supportsTools is false`() {
        assertFalse(
            "Unannotated providers should not claim tool support",
            FakeUnsupportedProvider.supportsTools,
        )
    }

    @Test
    fun `default chatWithTools wraps stream emits as TextDelta`() = runTest {
        val tools = ToolRegistry.build(
            specs = StoryvoxToolSpecs.ALL,
            lookup = { ToolHandler { ToolResult.Success("never called") } },
        )
        val events = FakeUnsupportedProvider.chatWithTools(
            messages = listOf(LlmMessage(LlmMessage.Role.user, "hi")),
            tools = tools,
        ).toList()
        // Only TextDelta events; tool registry was silently ignored.
        assertEquals(2, events.size)
        events.forEach { event ->
            assert(event is ChatStreamEvent.TextDelta) {
                "Expected TextDelta, got $event"
            }
        }
        assertEquals(
            "hello world",
            events.filterIsInstance<ChatStreamEvent.TextDelta>()
                .joinToString("") { it.text },
        )
    }
}
