package com.opencode.android.tool

import com.opencode.android.model.ExecuteResult
import com.opencode.android.model.ToolContext
import kotlinx.serialization.json.*
import java.io.File

class ApplyPatchTool : Tool(
    id = "apply_patch",
    description = """Apply a unified diff patch to files. Use this when the model needs to make multiple changes across a file and wants to provide a single patch instead of multiple edit commands.
The patch must be in standard unified diff format (diff -u old new).
Supports creating new files and deleting files via patches."""
) {
    override val parameters: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("patchText") {
                put("type", "string")
                put("description", "The full unified diff patch text to apply")
            }
        }
        putJsonArray("required") { add("patchText") }
    }

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ExecuteResult {
        val patchText = args["patchText"] as? String ?: return ExecuteResult("Error", "Missing patchText", isError = true)

        return try {
            val results = applyPatch(patchText)
            val successes = results.filter { it.isSuccess }
            val failures = results.filter { !it.isSuccess }

            val output = buildString {
                if (successes.isNotEmpty()) {
                    appendLine("Applied ${successes.size} change(s):")
                    successes.forEach { appendLine("  ✓ ${it.filePath}") }
                }
                if (failures.isNotEmpty()) {
                    appendLine("Failed ${failures.size} change(s):")
                    failures.forEach { appendLine("  ✗ ${it.filePath}: ${it.error}") }
                }
            }

            ExecuteResult(
                title = "Patch: ${successes.size} applied, ${failures.size} failed",
                output = output,
                isError = failures.isNotEmpty()
            )
        } catch (e: Exception) {
            ExecuteResult("Error", "Failed to apply patch: ${e.message}", isError = true)
        }
    }

    private data class PatchResult(val filePath: String, val isSuccess: Boolean, val error: String = "")

    private fun applyPatch(patchText: String): List<PatchResult> {
        val results = mutableListOf<PatchResult>()
        val hunks = parsePatch(patchText)

        hunks.forEach { (targetFile, operations) ->
            try {
                val file = File(targetFile)
                if (!file.exists() && operations.any { it.isAddition }) {
                    // New file
                    val content = operations.filter { it.isAddition }
                        .joinToString("\n") { it.lines.joinToString("\n") }
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    results.add(PatchResult(targetFile, true))
                } else if (file.exists()) {
                    var content = file.readText()
                    var modified = false
                    var failed = false

                    for (op in operations) {
                        when (op.type) {
                            OperationType.Delete -> {
                                if (content.contains(op.searchText)) {
                                    content = content.replaceFirst(op.searchText, "")
                                    modified = true
                                } else {
                                    failed = true
                                }
                            }
                            OperationType.Replace -> {
                                if (content.contains(op.searchText)) {
                                    content = content.replaceFirst(op.searchText, op.replacement)
                                    modified = true
                                } else {
                                    failed = true
                                }
                            }
                            OperationType.Insert -> {
                                content += "\n${op.lines.joinToString("\n")}"
                                modified = true
                            }
                        }
                    }

                    if (modified && !failed) {
                        file.writeText(content)
                        results.add(PatchResult(targetFile, true))
                    } else if (failed) {
                        results.add(PatchResult(targetFile, false, "Could not find exact context lines to match"))
                    } else {
                        results.add(PatchResult(targetFile, true))
                    }
                } else {
                    results.add(PatchResult(targetFile, false, "File not found: $targetFile"))
                }
            } catch (e: Exception) {
                results.add(PatchResult(targetFile, false, e.message ?: "Unknown error"))
            }
        }

        return results
    }

    private data class Hunk(val targetFile: String, val operations: List<PatchOperation>)
    private data class PatchOperation(
        val type: OperationType,
        val searchText: String = "",
        val replacement: String = "",
        val lines: List<String> = emptyList(),
        val isAddition: Boolean = false
    )
    private enum class OperationType { Delete, Replace, Insert }

    private fun parsePatch(patchText: String): List<Hunk> {
        val hunks = mutableListOf<Hunk>()
        val lines = patchText.lines()
        var currentFile: String? = null
        var currentOldLines = mutableListOf<String>()
        var currentNewLines = mutableListOf<String>()
        var inHunk = false

        for (line in lines) {
            when {
                line.startsWith("--- ") -> {
                    inHunk = false
                    // Skip the --- a/file line
                }
                line.startsWith("+++ ") -> {
                    currentFile = line.removePrefix("+++ ").trim()
                }
                line.startsWith("@@") -> {
                    // Finish previous hunk
                    if (inHunk && currentFile != null) {
                        val ops = parseHunkOperations(currentOldLines, currentNewLines)
                        if (ops.isNotEmpty()) {
                            hunks.add(Hunk(currentFile, ops))
                        }
                    }
                    currentOldLines.clear()
                    currentNewLines.clear()
                    inHunk = true
                }
                inHunk -> {
                    when {
                        line.startsWith("-") -> currentOldLines.add(line.removePrefix("-"))
                        line.startsWith("+") -> currentNewLines.add(line.removePrefix("+"))
                        line.startsWith(" ") -> {
                            currentOldLines.add(line.removePrefix(" "))
                            currentNewLines.add(line.removePrefix(" "))
                        }
                    }
                }
            }
        }

        // Last hunk
        if (inHunk && currentFile != null) {
            val ops = parseHunkOperations(currentOldLines, currentNewLines)
            if (ops.isNotEmpty()) {
                hunks.add(Hunk(currentFile, ops))
            }
        }

        if (hunks.isEmpty()) {
            // Fallback: treat whole patch as a single file replacement
            val cleanText = patchText
                .lines()
                .filterNot { it.startsWith("---") || it.startsWith("+++") || it.startsWith("@@") || it.startsWith("diff ") }
                .filter { it.startsWith("-") || it.startsWith("+") || it.startsWith(" ") }
                .joinToString("\n")

            if (cleanText.isNotBlank()) {
                // Try to guess target from patch
                val targetLine = patchText.lines().find { it.startsWith("+++ ") }
                val target = targetLine?.removePrefix("+++ ")?.trim() ?: "unknown"
                hunks.add(Hunk(target, listOf(PatchOperation(OperationType.Replace, cleanText, cleanText))))
            }
        }

        return hunks
    }

    private fun parseHunkOperations(oldLines: List<String>, newLines: List<String>): List<PatchOperation> {
        val ops = mutableListOf<PatchOperation>()

        if (oldLines.isEmpty() && newLines.isNotEmpty()) {
            // Pure addition
            ops.add(PatchOperation(OperationType.Insert, lines = newLines, isAddition = true))
        } else if (newLines.isEmpty() && oldLines.isNotEmpty()) {
            // Pure deletion
            ops.add(PatchOperation(OperationType.Delete, searchText = oldLines.joinToString("\n")))
        } else if (oldLines != newLines) {
            // Replacement
            ops.add(PatchOperation(OperationType.Replace, oldLines.joinToString("\n"), newLines.joinToString("\n")))
        }

        return ops
    }
}
