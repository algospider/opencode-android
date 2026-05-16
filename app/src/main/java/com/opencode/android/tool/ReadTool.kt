package com.opencode.android.tool

import com.opencode.android.model.ExecuteResult
import com.opencode.android.model.ToolContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

class ReadTool : Tool(
    id = "read",
    description = """Read the contents of a file at the given path. Files are read line-by-line.
You can optionally specify an offset (1-indexed) and limit to read only a portion.
Use this when you need to examine the contents of a single file.
For large files, specify offset and limit to read a specific portion.
When reading multiple files, use the glob tool first to find matching files, then read each one."""
) {
    override val parameters: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("filePath") {
                put("type", "string")
                put("description", "The absolute path to the file to read")
            }
            putJsonObject("offset") {
                put("type", "number")
                put("description", "The line number to start reading from (1-indexed)")
            }
            putJsonObject("limit") {
                put("type", "number")
                put("description", "The maximum number of lines to read")
            }
        }
        putJsonArray("required") { add("filePath") }
    }

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ExecuteResult {
        val filePath = args["filePath"] as? String ?: return ExecuteResult("Error", "Missing filePath", isError = true)
        val offset = (args["offset"] as? Number)?.toInt() ?: 0
        val limit = (args["limit"] as? Number)?.toInt() ?: 0

        val file = File(filePath)
        if (!file.exists()) return ExecuteResult("Error", "File not found: $filePath", isError = true)
        if (!file.isFile) return ExecuteResult("Error", "Not a file: $filePath", isError = true)

        val lines = file.readLines()
        val total = lines.size

        val startLine = if (offset > 0) offset else 1
        val endLine = if (limit > 0) minOf(startLine + limit - 1, total) else total

        val content = if (offset > 0 || limit > 0) {
            val slice = lines.subList(
                (startLine - 1).coerceIn(0, total),
                endLine.coerceIn(0, total)
            )
            slice.mapIndexed { i, line -> "${startLine + i}: $line" }.joinToString("\n")
        } else {
            lines.mapIndexed { i, line -> "${i + 1}: $line" }.joinToString("\n")
        }

        val metadata = mapOf(
            "totalLines" to total.toString(),
            "startLine" to startLine.toString(),
            "endLine" to endLine.toString()
        )

        return ExecuteResult("Read ${file.name}", content, metadata)
    }
}
