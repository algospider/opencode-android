package com.opencode.android.tool

import com.opencode.android.model.ExecuteResult
import com.opencode.android.model.ToolContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

class GrepTool : Tool(
    id = "grep",
    description = """Search file contents for a regex pattern in the filesystem.
Returns matching file paths with line numbers.
Use this when you need to find where certain code, functions, or text patterns are used.
Supports filtering by file extension pattern."""
) {
    override val parameters: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("pattern") {
                put("type", "string")
                put("description", "The regex pattern to search for in file contents")
            }
            putJsonObject("path") {
                put("type", "string")
                put("description", "The directory to search in")
            }
            putJsonObject("include") {
                put("type", "string")
                put("description", "File pattern to filter (e.g. *.kt, *.{ts,tsx})")
            }
        }
        putJsonArray("required") { add("pattern") }
    }

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ExecuteResult {
        val pattern = args["pattern"] as? String ?: return ExecuteResult("Error", "Missing pattern", isError = true)
        val searchPath = args["path"] as? String ?: "."
        val include = args["include"] as? String?

        val rootDir = File(searchPath)
        if (!rootDir.exists()) return ExecuteResult("Error", "Directory not found: $searchPath", isError = true)

        val regex = try {
            Regex(pattern, setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
        } catch (e: Exception) {
            return ExecuteResult("Error", "Invalid regex pattern: ${e.message}", isError = true)
        }

        val includeRegex = include?.let { globToRegex(it) }
        val results = mutableListOf<String>()
        var matchCount = 0

        rootDir.walkTopDown()
            .maxDepth(20)
            .filter { file ->
                file.isFile &&
                    !file.name.startsWith(".") &&
                    (includeRegex == null || includeRegex.matches(file.name)) &&
                    !isBinaryExtension(file.name)
            }
            .forEach { file ->
                try {
                    file.useLines { lines ->
                        lines.forEachIndexed { lineNum, line ->
                            if (regex.containsMatchIn(line) && results.size < 50) {
                                results.add("${file.absolutePath}:${lineNum + 1}: ${line.trim().take(200)}")
                                matchCount++
                            }
                        }
                    }
                } catch (_: Exception) { }
            }

        return if (results.isEmpty()) {
            ExecuteResult("No matches", "No matches found for pattern: $pattern")
        } else {
            ExecuteResult("Found $matchCount matches", results.joinToString("\n"))
        }
    }

    private fun globToRegex(glob: String): Regex {
        val regexStr = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            when (val c = glob[i]) {
                '*' -> regexStr.append(".*")
                '?' -> regexStr.append(".")
                '.' -> regexStr.append("\\.")
                '{' -> {
                    val end = glob.indexOf('}', i)
                    val parts = glob.substring(i + 1, end).split(",")
                    regexStr.append("(")
                    regexStr.append(parts.joinToString("|") { Regex.escape(it) })
                    regexStr.append(")")
                    i = end
                }
                else -> regexStr.append(Regex.escape(c.toString()))
            }
            i++
        }
        regexStr.append("$")
        return Regex(regexStr.toString())
    }

    private fun isBinaryExtension(name: String): Boolean {
        val binaryExts = setOf("png", "jpg", "jpeg", "gif", "bmp", "ico", "webp",
            "mp3", "mp4", "avi", "mov", "mkv", "flac", "ogg", "wav",
            "zip", "tar", "gz", "bz2", "7z", "rar", "jar", "aar", "apk",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "ttf", "otf", "woff", "woff2", "eot",
            "dex", "class", "o", "so", "dylib", "dll", "exe", "elf",
            "db", "sqlite", "keystore", "jks", "pfx")
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in binaryExts || ext.length > 10
    }
}
