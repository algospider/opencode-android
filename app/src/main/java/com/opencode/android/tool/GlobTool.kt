@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.opencode.android.tool

import com.opencode.android.model.ExecuteResult
import com.opencode.android.model.ToolContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

class GlobTool : Tool(
    id = "glob",
    description = """Search for files matching a glob pattern in the filesystem.
Supports glob patterns like **/*.kt, src/**/*.ts, etc.
Use this when you need to find files by name patterns in a directory.
Results are sorted by modification time (most recent first)."""
) {
    override val parameters: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("pattern") {
                put("type", "string")
                put("description", "The glob pattern to match files against (e.g. **/*.kt)")
            }
            putJsonObject("path") {
                put("type", "string")
                put("description", "The directory to search in")
            }
        }
        putJsonArray("required") { add("pattern") }
    }

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ExecuteResult {
        val pattern = args["pattern"] as? String ?: return ExecuteResult("Error", "Missing pattern", isError = true)
        val searchPath = args["path"] as? String ?: "."

        val rootDir = File(searchPath)
        if (!rootDir.exists()) return ExecuteResult("Error", "Directory not found: $searchPath", isError = true)

        val regex = globToRegex(pattern)
        val results = mutableListOf<File>()

        rootDir.walkTopDown()
            .maxDepth(20)
            .filter { it.isFile && regex.matches(it.relativeTo(rootDir).path.replace("\\", "/")) }
            .sortedByDescending { it.lastModified() }
            .take(100)
            .forEach { results.add(it) }

        val output = if (results.isEmpty()) {
            "No files found matching pattern: $pattern"
        } else {
            results.joinToString("\n") { it.absolutePath }
        }

        return ExecuteResult("Found ${results.size} files", output)
    }

    private fun globToRegex(glob: String): Regex {
        val regexStr = StringBuilder()
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                    regexStr.append(".*")
                    i += 2
                    if (i < glob.length && (glob[i] == '/' || glob[i] == '\\')) i++
                    continue
                }
                c == '*' -> regexStr.append("[^/]*")
                c == '?' -> regexStr.append("[^/]")
                c == '.' -> regexStr.append("\\.")
                c == '/' || c == '\\' -> regexStr.append("/")
                else -> regexStr.append(c)
            }
            i++
        }
        return Regex("^${regexStr}$")
    }
}
