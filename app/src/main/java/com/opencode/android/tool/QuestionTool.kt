package com.opencode.android.tool

import com.opencode.android.model.ExecuteResult
import com.opencode.android.model.ToolContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

data class QuestionRequest(
    val id: String,
    val question: String,
    val header: String,
    val options: List<QuestionOption>,
    val multiple: Boolean
)

data class QuestionOption(
    val label: String,
    val description: String
)

data class QuestionResponse(
    val id: String,
    val answers: List<String>
)

class QuestionTool : Tool(
    id = "question",
    description = """Ask the user a structured question with multiple choice options.
Use this when you need the user to make a decision, provide input, or clarify requirements.
You can present options with descriptions and allow single or multiple selections."""
) {
    private val _requests = MutableSharedFlow<QuestionRequest>(extraBufferCapacity = 16)
    val requests: SharedFlow<QuestionRequest> = _requests.asSharedFlow()

    private val _responses = MutableSharedFlow<QuestionResponse>(extraBufferCapacity = 16)

    private var requestCounter = 0

    override val parameters: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("question") {
                put("type", "string")
                put("description", "The question to ask the user")
            }
            putJsonObject("header") {
                put("type", "string")
                put("description", "A short label for the question (max 30 chars)")
            }
            putJsonArray("options") {
                addJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("label") {
                            put("type", "string")
                            put("description", "Display text for the option (1-5 words)")
                        }
                        putJsonObject("description") {
                            put("type", "string")
                            put("description", "Explanation of the choice")
                        }
                    }
                    putJsonArray("required") { add("label"); add("description") }
                }
            }
            putJsonObject("multiple") {
                put("type", "boolean")
                put("description", "Allow multiple selections (default: false)")
            }
        }
        putJsonArray("required") { add("question"); add("header"); add("options") }
    }

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ExecuteResult {
        val question = args["question"] as? String ?: return ExecuteResult("Error", "Missing question", isError = true)
        val header = args["header"] as? String ?: "Question"
        val multiple = args["multiple"] as? Boolean ?: false

        @Suppress("UNCHECKED_CAST")
        val rawOptions = args["options"] as? List<Map<String, Any?>> ?: return ExecuteResult("Error", "Missing options", isError = true)

        val options = rawOptions.map { opt ->
            QuestionOption(
                label = opt["label"] as? String ?: "",
                description = opt["description"] as? String ?: ""
            )
        }

        val requestId = "q_${++requestCounter}_${System.currentTimeMillis()}"
        val request = QuestionRequest(requestId, question, header, options, multiple)

        _requests.emit(request)

        val response = _responses.first { it.id == requestId }
        return if (response.answers.isEmpty()) {
            ExecuteResult("User cancelled", "User chose not to answer")
        } else {
            ExecuteResult("User answered", response.answers.joinToString("\n"))
        }
    }

    suspend fun submitResponse(response: QuestionResponse) {
        _responses.emit(response)
    }
}
