package `in`.jphe.storyvox.llm.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Issue #216 — declarative description of a single AI-callable
 * function. Pairs with [ToolHandler], which executes the function
 * against storyvox state.
 *
 * The schema is written by hand (one [JsonObject] per parameter)
 * rather than derived from a Kotlin type. v1 has five tools with at
 * most two scalar params apiece — pulling in a JSON-schema generator
 * library (or `kotlinx.serialization`'s `descriptor` walk) would add
 * far more weight than the surface justifies. Hand-rolled schemas
 * also let us write descriptions tuned for an LLM audience without
 * fighting reflection.
 *
 * The same [ToolSpec] feeds both Anthropic-shape and OpenAI-shape
 * tool advertisements; provider classes adapt the shape at the wire
 * layer. See [`in`.jphe.storyvox.llm.provider.ClaudeApiProvider]
 * and [`in`.jphe.storyvox.llm.provider.OpenAiApiProvider].
 */
data class ToolSpec(
    /** Snake_case name. Both Anthropic and OpenAI accept matching
     *  `^[a-zA-Z0-9_-]{1,64}$` — we stay conservative on snake_case
     *  so the model can't accidentally call a name we didn't register. */
    val name: String,
    /** One-paragraph description the model uses to decide WHEN to
     *  call this tool. Be specific about what triggers the call
     *  ("user asks to add the active book to a shelf") rather than
     *  what the tool does mechanically — the model already infers the
     *  mechanics from the parameters. */
    val description: String,
    /** Ordered list of named parameters. Order matters only for the
     *  human reader of the schema — JSON has no positional args. */
    val parameters: List<ToolParameter> = emptyList(),
) {
    /** Build an Anthropic-shape `input_schema` JsonObject for this
     *  tool. Anthropic expects:
     *  `{type: "object", properties: {...}, required: [...]}`. */
    fun toAnthropicInputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            parameters.forEach { p ->
                put(p.name, p.toJsonSchema())
            }
        })
        put("required", buildJsonArray {
            parameters.filter { it.required }.forEach { add(it.name) }
        })
    }

    /** OpenAI's `function.parameters` is the same JSON Schema as
     *  Anthropic's `input_schema`. Same shape, different wrapper. */
    fun toOpenAiParameters(): JsonObject = toAnthropicInputSchema()
}

/**
 * A single named parameter on a [ToolSpec]. The four supported types
 * cover every v1 tool — adding more is a one-liner here plus a wire
 * branch in [toJsonSchema].
 */
sealed class ToolParameter {
    abstract val name: String
    abstract val description: String
    abstract val required: Boolean

    abstract fun toJsonSchema(): JsonObject

    data class StringParam(
        override val name: String,
        override val description: String,
        override val required: Boolean = true,
        /** Optional enum-like constraint. Models do a markedly better
         *  job picking valid values when the schema lists them rather
         *  than just describing them in prose. */
        val allowedValues: List<String>? = null,
    ) : ToolParameter() {
        override fun toJsonSchema(): JsonObject = buildJsonObject {
            put("type", "string")
            put("description", description)
            allowedValues?.let { values ->
                put("enum", buildJsonArray {
                    values.forEach { add(it) }
                })
            }
        }
    }

    data class IntParam(
        override val name: String,
        override val description: String,
        override val required: Boolean = true,
        val min: Int? = null,
        val max: Int? = null,
    ) : ToolParameter() {
        override fun toJsonSchema(): JsonObject = buildJsonObject {
            put("type", "integer")
            put("description", description)
            min?.let { put("minimum", it) }
            max?.let { put("maximum", it) }
        }
    }

    data class FloatParam(
        override val name: String,
        override val description: String,
        override val required: Boolean = true,
        val min: Float? = null,
        val max: Float? = null,
    ) : ToolParameter() {
        override fun toJsonSchema(): JsonObject = buildJsonObject {
            put("type", "number")
            put("description", description)
            min?.let { put("minimum", it.toDouble()) }
            max?.let { put("maximum", it.toDouble()) }
        }
    }
}

