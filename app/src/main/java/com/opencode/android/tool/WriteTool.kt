package com.opencode.android.tool

import com.opencode.android.model.ExecuteResult
import com.opencode.android.model.ToolContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

class WriteTool : Tool(
    id = "write",
    description = """Write content to a file at the given path. Creates the file and any parent directories if they don't exist.
Use this when you need to create a new file or completely overwrite an existing file.
For making targeted changes to existing files, prefer the edit tool instead."""
) {
    override val parameters: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("filePath") {
                put("type", "string")
                put("description", "The absolute path to the file to write")
            }
            putJsonObject("content") {
                put("type", "string")
                put("description", "The full content to write to the file")
            }
        }
        putJsonArray("required") { add("filePath"); add("content") }
    }

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ExecuteResult {
        val filePath = args["filePath"] as? String ?: return ExecuteResult("Error", "Missing filePath", isError = true)
        val content = args["content"] as? String ?: return ExecuteResult("Error", "Missing content", isError = true)

        val file = File(filePath)
        file.parentFile?.mkdirs()

        file.writeText(content)

        return ExecuteResult("Wrote ${file.name}", "Written ${content.length} bytes to $filePath")
    }
}
