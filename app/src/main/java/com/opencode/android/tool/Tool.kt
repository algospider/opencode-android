package com.opencode.android.tool

import com.opencode.android.model.ExecuteResult
import com.opencode.android.model.ToolContext
import kotlinx.serialization.json.JsonElement

abstract class Tool(
    val id: String,
    val description: String
) {
    abstract val parameters: JsonElement
    abstract suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ExecuteResult

    open fun formatValidationError(error: Exception): String =
        "Invalid arguments for $id: ${error.message}"

    companion object {
        fun jsonSchema(properties: Map<String, JsonSchema>, required: List<String> = emptyList()): JsonSchemaObject {
            return JsonSchemaObject(
                type = "object",
                properties = properties,
                required = required
            )
        }
    }
}

data class JsonSchemaObject(
    val type: String = "object",
    val properties: Map<String, JsonSchema> = emptyMap(),
    val required: List<String> = emptyList()
)

sealed class JsonSchema {
    data class StringSchema(val description: String = "") : JsonSchema()
    data class NumberSchema(val description: String = "") : JsonSchema()
    data class BooleanSchema(val description: String = "") : JsonSchema()
    data class ArraySchema(val description: String = "", val items: JsonSchema? = null) : JsonSchema()
    data class ObjectSchema(val description: String = "", val properties: Map<String, JsonSchema> = emptyMap()) : JsonSchema()
    data class EnumSchema(val description: String = "", val values: List<String> = emptyList()) : JsonSchema()
}
