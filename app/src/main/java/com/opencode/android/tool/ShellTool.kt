package com.opencode.android.tool

import com.opencode.android.model.ExecuteResult
import com.opencode.android.model.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit

class ShellTool : Tool(
    id = "bash",
    description = """Execute shell commands in the terminal. Use this to run build tools, git commands, run scripts, install packages, or any command-line operation.
Commands run with a 120-second timeout by default. Output is limited to 2000 lines / 50KB.
For long-running commands, the output shows progress every 5 seconds.
The current working directory defaults to the project root."""
) {
    override val parameters: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("command") {
                put("type", "string")
                put("description", "The shell command to execute")
            }
            putJsonObject("description") {
                put("type", "string")
                put("description", "Brief description of what the command does (5-10 words)")
            }
            putJsonObject("timeout") {
                put("type", "number")
                put("description", "Timeout in milliseconds (default 120000)")
            }
            putJsonObject("workdir") {
                put("type", "string")
                put("description", "Working directory for the command")
            }
        }
        putJsonArray("required") { add("command"); add("description") }
    }

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ExecuteResult {
        val command = args["command"] as? String ?: return ExecuteResult("Error", "Missing command", isError = true)
        val timeout = (args["timeout"] as? Number)?.toLong() ?: 120_000L
        val workdir = args["workdir"] as? String

        return withContext(Dispatchers.IO) {
            try {
                val processBuilder = ProcessBuilder()
                processBuilder.redirectErrorStream(true)

                // Determine shell
                val osName = System.getProperty("os.name", "").lowercase()
                if (osName.contains("windows")) {
                    processBuilder.command("cmd", "/c", command)
                } else {
                    processBuilder.command("sh", "-c", command)
                }

                if (workdir != null) {
                    processBuilder.directory(File(workdir))
                }

                val env = processBuilder.environment()
                env["TERM"] = "xterm-256color"
                env["SHELL"] = if (osName.contains("windows")) "cmd" else "/system/bin/sh"

                val process = processBuilder.start()
                val output = StringBuilder()
                val errorOutput = StringBuilder()
                var lineCount = 0
                val maxLines = 2000

                // Read stdout
                val stdin = process.inputStream.bufferedReader()
                var line: String?
                var timedOut = false

                val startTime = System.currentTimeMillis()

                while (true) {
                    val remaining = timeout - (System.currentTimeMillis() - startTime)
                    if (remaining <= 0) {
                        timedOut = true
                        process.destroyForcibly()
                        break
                    }

                    if (stdin.ready()) {
                        line = stdin.readLine()
                        if (line == null) break
                        if (lineCount < maxLines) {
                            output.appendLine(line)
                        }
                        lineCount++
                    } else {
                        if (!process.isAlive) {
                            // Read remaining output
                            stdin.lines().forEach { l ->
                                if (lineCount < maxLines) output.appendLine(l)
                                lineCount++
                            }
                            break
                        }
                        Thread.sleep(50)
                    }
                }

                val exitCode = if (timedOut) -1 else process.waitFor(5, TimeUnit.SECONDS).let { if (it) process.exitValue() else -1 }
                val duration = System.currentTimeMillis() - startTime

                val header = "Exit code: $exitCode | Duration: ${duration}ms | Lines: $lineCount"
                val body = output.toString().ifBlank { "(no output)" }
                val truncated = if (lineCount > maxLines) "\n... (output truncated at $maxLines lines)" else ""

                val result = buildString {
                    appendLine(header)
                    appendLine(body)
                    append(truncated)
                    if (timedOut) append("\n⚠ Command timed out after ${timeout}ms")
                }

                val title = if (exitCode == 0) "✓ $command" else "✗ $command (exit $exitCode)"

                ExecuteResult(
                    title = title,
                    output = result,
                    metadata = mapOf(
                        "exitCode" to exitCode.toString(),
                        "duration" to "${duration}ms",
                        "timedOut" to timedOut.toString()
                    ),
                    isError = exitCode != 0 && !timedOut
                )
            } catch (e: Exception) {
                ExecuteResult("Error", "Shell execution failed: ${e.message}", isError = true)
            }
        }
    }
}
