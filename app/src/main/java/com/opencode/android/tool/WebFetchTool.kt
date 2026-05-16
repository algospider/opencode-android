package com.opencode.android.tool

import com.opencode.android.model.ExecuteResult
import com.opencode.android.model.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class WebFetchTool : Tool(
    id = "webfetch",
    description = """Fetch and retrieve the content of a web page at a given URL.
Returns the content in the specified format (markdown, text, or HTML).
Use this when you need to read documentation, articles, or any web-based information.
The response is converted to markdown by default for easy reading."""
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override val parameters: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("url") {
                put("type", "string")
                put("description", "The URL to fetch content from")
            }
            putJsonObject("format") {
                put("type", "string")
                put("enum", "[\"text\",\"markdown\",\"html\"]".let { Json.parseToJsonElement(it) })
                put("description", "Output format (text, markdown, or html)")
            }
            putJsonObject("timeout") {
                put("type", "number")
                put("description", "Timeout in seconds (max 120)")
            }
        }
        putJsonArray("required") { add("url") }
    }

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ExecuteResult {
        val url = args["url"] as? String ?: return ExecuteResult("Error", "Missing URL", isError = true)
        val format = args["format"] as? String ?: "markdown"
        val timeout = (args["timeout"] as? Number)?.toInt() ?: 30

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .addHeader("Accept", when (format) {
                        "markdown" -> "text/markdown,text/html,text/plain;q=0.8"
                        "text" -> "text/plain,text/html;q=0.8"
                        else -> "text/html,application/xhtml+xml;q=0.9"
                    })
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext ExecuteResult("Error", "HTTP ${response.code}: ${body.take(200)}", isError = true)
                }

                val contentType = response.header("Content-Type", "").lowercase()
                val result = when {
                    contentType.contains("image/") -> "[Image: $url]"
                    format == "markdown" || format == "text" -> {
                        if (contentType.contains("html")) stripHtml(body)
                        else body
                    }
                    else -> body
                }

                val truncated = if (result.length > 50000) {
                    "${result.take(50000)}\n\n... [truncated at 50000 characters]"
                } else result

                ExecuteResult("Fetched $url", truncated)
            } catch (e: Exception) {
                ExecuteResult("Error", "Failed to fetch $url: ${e.message}", isError = true)
            }
        }
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("&#\\d+;")) { match ->
                match.value.drop(2).dropLast(1).toIntOrNull()?.toChar()?.toString() ?: match.value
            }
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }
}
