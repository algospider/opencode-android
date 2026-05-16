package com.opencode.android.tool

import com.opencode.android.model.ExecuteResult
import com.opencode.android.model.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class WebSearchTool : Tool(
    id = "websearch",
    description = """Search the web for real-time information. Use this when you need current data, documentation, news, or answers to questions that require up-to-date knowledge.
Supports locating web pages, fetching content, and summarizing findings. Results include titles, URLs, and snippets.
You can configure search depth (auto/fast/deep) and whether to crawl live pages."""
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json".toMediaType()

    // Parallel Search MCP endpoint (no API key needed)
    private val parallelUrl = "https://search.parallel.ai/mcp"
    // Exa MCP endpoint (optional API key)
    private val exaUrl = "https://mcp.exa.ai/mcp"

    override val parameters: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string"); put("description", "Web search query")
            }
            putJsonObject("numResults") {
                put("type", "number"); put("description", "Number of search results to return (default 8)")
            }
            putJsonObject("livecrawl") {
                put("type", "string"); put("enum", "[\"fallback\",\"preferred\"]".let { Json.parseToJsonElement(it) })
                put("description", "Live crawling mode")
            }
            putJsonObject("type") {
                put("type", "string"); put("enum", "[\"auto\",\"fast\",\"deep\"]".let { Json.parseToJsonElement(it) })
                put("description", "Search type")
            }
        }
        putJsonArray("required") { add("query") }
    }

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ExecuteResult {
        val query = args["query"] as? String ?: return ExecuteResult("Error", "Missing query", isError = true)
        val numResults = (args["numResults"] as? Number)?.toInt() ?: 8
        val livecrawl = args["livecrawl"] as? String ?: "fallback"
        val searchType = args["type"] as? String ?: "auto"

        return withContext(Dispatchers.IO) {
            try {
                // Try Parallel search first, fallback to Exa
                val result = try {
                    parallelSearch(query, numResults)
                } catch (_: Exception) {
                    try {
                        exaSearch(query, numResults)
                    } catch (_: Exception) {
                        googleSearchFallback(query, numResults)
                    }
                }
                ExecuteResult("Web search: $query", result)
            } catch (e: Exception) {
                ExecuteResult("Error", "Search failed: ${e.message}", isError = true)
            }
        }
    }

    private suspend fun parallelSearch(query: String, numResults: Int): String {
        val requestBody = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "search")
                putJsonObject("arguments") {
                    put("objective", query)
                    putJsonArray("search_queries") { add(query) }
                    put("num_results", numResults)
                }
            }
        }

        val request = Request.Builder()
            .url(parallelUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "opencode-android/0.1.0")
            .post(requestBody.toString().toRequestBody(mediaType))
            .build()

        val response = client.newCall(request).await()
        val body = response.body?.string() ?: ""

        // Parse SSE or JSON response
        return if (body.contains("\"type\":\"text\"")) {
            parseMcpResponse(body)
        } else {
            // Direct JSON fallback
            try {
                val parsed = json.parseToJsonElement(body).jsonObject
                val content = parsed["result"]?.jsonObject?.get("content")?.jsonArray
                content?.joinToString("\n\n") { part ->
                    part.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
                } ?: "No results found"
            } catch (_: Exception) {
                body.take(2000)
            }
        }
    }

    private suspend fun exaSearch(query: String, numResults: Int): String {
        val requestBody = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "search")
                putJsonObject("arguments") {
                    put("query", query)
                    put("type", "auto")
                    put("numResults", numResults)
                }
            }
        }

        val url = exaUrl
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody(mediaType))
            .build()

        val response = client.newCall(request).await()
        val body = response.body?.string() ?: ""
        return parseMcpResponse(body)
    }

    private suspend fun googleSearchFallback(query: String, numResults: Int): String {
        // Use DuckDuckGo's instant answer API (free, no key needed)
        val url = "https://api.duckduckgo.com/?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json&no_html=1"
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).await()
        val body = response.body?.string() ?: ""

        return try {
            val parsed = json.parseToJsonElement(body).jsonObject
            val abstractText = parsed["AbstractText"]?.jsonPrimitive?.contentOrNull ?: ""
            val answer = parsed["Answer"]?.jsonPrimitive?.contentOrNull ?: ""
            val results = parsed["RelatedTopics"]?.jsonArray
                ?.take(numResults)
                ?.joinToString("\n\n") { topic ->
                    val obj = topic.jsonObject
                    val text = obj["Text"]?.jsonPrimitive?.contentOrNull ?: ""
                    val url2 = obj["FirstURL"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isNotBlank()) "• $text\n  $url2" else ""
                }
                ?: ""

            buildString {
                if (answer.isNotBlank()) appendLine("Answer: $answer")
                if (abstractText.isNotBlank()) appendLine("Summary: $abstractText")
                if (results.isNotBlank()) appendLine("Results:\n$results")
                if (isBlank()) append("No results found for: $query")
            }
        } catch (_: Exception) {
            "No results found"
        }
    }

    private fun parseMcpResponse(body: String): String {
        // SSE lines: "data: {...}"
        val lines = body.lines().filter { it.startsWith("data: ") }
        if (lines.isEmpty()) {
            // Try as direct JSON
            return try {
                val parsed = json.parseToJsonElement(body).jsonObject
                parsed["result"]?.jsonObject?.get("content")?.jsonArray?.joinToString("\n") { part ->
                    part.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
                } ?: body.take(2000)
            } catch (_: Exception) { body.take(2000) }
        }

        return lines.mapNotNull { line ->
            val data = line.removePrefix("data: ")
            try {
                val parsed = json.parseToJsonElement(data).jsonObject
                parsed["type"]?.jsonPrimitive?.contentOrNull?.let { type ->
                    when (type) {
                        "text" -> parsed["text"]?.jsonPrimitive?.contentOrNull
                        "result" -> {
                            val content = parsed["content"]?.jsonArray
                            content?.joinToString("\n") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: "" }
                        }
                        else -> null
                    }
                }
            } catch (_: Exception) { null }
        }.filterNotNull().joinToString("\n").ifEmpty { "No results found" }
    }

    private suspend fun Call.await(): Response {
        return withContext(Dispatchers.IO) {
            try {
                execute()
            } catch (e: IOException) {
                throw e
            }
        }
    }
}
