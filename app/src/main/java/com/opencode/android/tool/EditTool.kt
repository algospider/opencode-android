package com.opencode.android.tool

import com.opencode.android.model.ExecuteResult
import com.opencode.android.model.ToolContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

class EditTool : Tool(
    id = "edit",
    description = """Make targeted edits to a file by finding and replacing exact text.
The oldString must match exactly (including whitespace) — it will be replaced by newString.
Use this for surgical changes to existing files instead of rewriting the entire file.
When you need to add new code at the end of a file, match the last line as oldString and provide the new content as newString."""
) {
    override val parameters: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("filePath") {
                put("type", "string")
                put("description", "The absolute path to the file to edit")
            }
            putJsonObject("oldString") {
                put("type", "string")
                put("description", "The exact text to find and replace")
            }
            putJsonObject("newString") {
                put("type", "string")
                put("description", "The replacement text")
            }
            putJsonObject("replaceAll") {
                put("type", "boolean")
                put("description", "Replace all occurrences instead of just the first")
            }
        }
        putJsonArray("required") { add("filePath"); add("oldString"); add("newString") }
    }

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ExecuteResult {
        val filePath = args["filePath"] as? String ?: return ExecuteResult("Error", "Missing filePath", isError = true)
        val oldString = args["oldString"] as? String ?: return ExecuteResult("Error", "Missing oldString", isError = true)
        val newString = args["newString"] as? String ?: return ExecuteResult("Error", "Missing newString", isError = true)
        val replaceAll = args["replaceAll"] as? Boolean ?: false

        val file = File(filePath)
        if (!file.exists()) return ExecuteResult("Error", "File not found: $filePath", isError = true)

        val content = file.readText()

        val result = if (replaceAll) {
            if (!content.contains(oldString)) {
                return ExecuteResult("Error", "oldString not found in file content", isError = true)
            }
            content.replace(oldString, newString)
        } else {
            if (!content.contains(oldString)) {
                return ExecuteResult("Error", "oldString not found in file content. Provide more surrounding context.", isError = true)
            }
            content.replaceFirst(oldString, newString)
        }

        file.writeText(result)
        return ExecuteResult("Edited ${file.name}", "Successfully applied edit to $filePath")
    }
}
