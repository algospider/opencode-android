package com.opencode.android.tool

class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()

    init {
        // File tools
        register(ReadTool())
        register(WriteTool())
        register(EditTool())
        register(GlobTool())
        register(GrepTool())
        register(ApplyPatchTool())

        // Web tools
        register(WebSearchTool())
        register(WebFetchTool())

        // Shell & Git tools
        register(ShellTool())
        register(GitInitTool())
        register(GitCloneTool())
        register(GitStatusTool())
        register(RepoOverviewTool())

        // Interaction tools
        register(QuestionTool())
    }

    fun register(tool: Tool) {
        tools[tool.id] = tool
    }

    fun get(id: String): Tool? = tools[id]

    fun getAll(): List<Tool> = tools.values.toList()

    fun getQuestionTool(): QuestionTool? = tools["question"] as? QuestionTool

    fun getToolDefs(agent: String = "build"): List<com.opencode.android.model.ToolDef> {
        return tools.values.map { tool ->
            com.opencode.android.model.ToolDef(
                id = tool.id,
                description = tool.description,
                parameters = tool.parameters
            )
        }
    }

    companion object {
        @Volatile
        private var instance: ToolRegistry? = null

        fun getInstance(): ToolRegistry {
            return instance ?: synchronized(this) {
                instance ?: ToolRegistry().also { instance = it }
            }
        }
    }
}
