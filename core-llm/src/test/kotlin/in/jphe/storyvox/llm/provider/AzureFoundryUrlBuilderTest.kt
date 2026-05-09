package `in`.jphe.storyvox.llm.provider

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-Kotlin URL-builder coverage. The two Azure Foundry deployment
 * modes have visibly different URL templates and the templating rules
 * are easy to break with a typo (api-version drift, missing slash,
 * lowercased path segments). Cheap to lock down without spinning up
 * MockWebServer.
 */
class AzureFoundryUrlBuilderTest {

    @Test
    fun `deployed mode renders openai-deployments path`() {
        val url = AzureFoundryProvider.buildUrl(
            endpoint = "https://my-resource.openai.azure.com",
            model = "gpt-4o-prod",
            serverless = false,
        )
        assertEquals(
            "https://my-resource.openai.azure.com/openai/deployments/gpt-4o-prod" +
                "/chat/completions?api-version=2024-12-01-preview",
            url,
        )
    }

    @Test
    fun `serverless mode renders models path with model in body not url`() {
        val url = AzureFoundryProvider.buildUrl(
            endpoint = "https://my-project.services.ai.azure.com",
            model = "Llama-3.3-70B-Instruct",
            serverless = true,
        )
        // Note: serverless URL does NOT contain the model id — that
        // goes in the body. Asserting the absence is part of the
        // contract.
        assertEquals(
            "https://my-project.services.ai.azure.com/models/chat/completions" +
                "?api-version=2024-12-01-preview",
            url,
        )
    }

    @Test
    fun `trailing slash on endpoint is tolerated`() {
        val deployed = AzureFoundryProvider.buildUrl(
            endpoint = "https://r.openai.azure.com/",
            model = "m",
            serverless = false,
        )
        val serverless = AzureFoundryProvider.buildUrl(
            endpoint = "https://r.services.ai.azure.com/",
            model = "m",
            serverless = true,
        )
        assertEquals(
            "https://r.openai.azure.com/openai/deployments/m/chat/completions" +
                "?api-version=2024-12-01-preview",
            deployed,
        )
        assertEquals(
            "https://r.services.ai.azure.com/models/chat/completions" +
                "?api-version=2024-12-01-preview",
            serverless,
        )
    }

    @Test
    fun `serverless ignores model in url so different deployment names produce same url`() {
        val a = AzureFoundryProvider.buildUrl("https://x", "m1", serverless = true)
        val b = AzureFoundryProvider.buildUrl("https://x", "m2", serverless = true)
        assertEquals(a, b)
    }
}
