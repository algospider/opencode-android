package com.opencode.android.tool

import com.opencode.android.model.ExecuteResult
import com.opencode.android.model.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File

class GitInitTool : Tool(
    id = "git_init",
    description = """Initialize a new git repository at the specified path.
Creates a .git directory and initializes version control.
Use this when starting a new project or adding git to an existing directory."""
) {
    override val parameters: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Directory path to initialize as git repository")
            }
            putJsonObject("branch") {
                put("type", "string")
                put("description", "Initial branch name (default: main)")
            }
        }
        putJsonArray("required") { add("path") }
    }

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ExecuteResult {
        val path = args["path"] as? String ?: return ExecuteResult("Error", "Missing path", isError = true)
        val branch = args["branch"] as? String ?: "main"

        return withContext(Dispatchers.IO) {
            try {
                val dir = File(path)
                if (!dir.exists()) dir.mkdirs()

                val gitDir = File(dir, ".git")
                if (gitDir.exists()) {
                    return@withContext ExecuteResult("Already a git repo", "Git repository already exists at $path")
                }

                runCommand(path, "git", "init", "-b", branch)
                runCommand(path, "git", "config", "user.email", "opencode@android")
                runCommand(path, "git", "config", "user.name", "OpenCode")

                ExecuteResult("Git init", "Initialized git repository at $path with branch '$branch'")
            } catch (e: Exception) {
                ExecuteResult("Error", "Git init failed: ${e.message}", isError = true)
            }
        }
    }
}

class GitCloneTool : Tool(
    id = "repo_clone",
    description = """Clone a git repository from a URL into the local filesystem.
Supports full URLs (https://github.com/owner/repo) and shorthand (owner/repo).
The repository is cloned into a managed cache directory."""
) {
    override val parameters: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("repository") {
                put("type", "string")
                put("description", "Git URL or owner/repo shorthand (e.g. https://github.com/user/repo or user/repo)")
            }
            putJsonObject("path") {
                put("type", "string")
                put("description", "Local path to clone into")
            }
            putJsonObject("branch") {
                put("type", "string")
                put("description", "Branch to clone (default: main/default branch)")
            }
        }
        putJsonArray("required") { add("repository") }
    }

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ExecuteResult {
        val repository = args["repository"] as? String ?: return ExecuteResult("Error", "Missing repository URL", isError = true)
        val branch = args["branch"] as? String
        val path = args["path"] as? String

        return withContext(Dispatchers.IO) {
            try {
                // Convert shorthand owner/repo to full URL
                val url = if (repository.contains("://") || repository.contains("@")) {
                    repository
                } else if (repository.contains("/")) {
                    "https://github.com/$repository.git"
                } else {
                    return@withContext ExecuteResult("Error", "Invalid repository format. Use owner/repo or full URL", isError = true)
                }

                // Determine target path
                val repoName = url.substringAfterLast("/").removeSuffix(".git").ifBlank { "repo" }
                val targetPath = path ?: "${ctx.sessionId}_$repoName"
                val targetDir = File(targetPath)

                if (targetDir.exists() && File(targetDir, ".git").exists()) {
                    return@withContext ExecuteResult("Already cloned", "Repository already exists at $targetPath")
                }

                val cmd = mutableListOf("git", "clone", "--depth=1")
                if (branch != null) {
                    cmd.add("--branch")
                    cmd.add(branch)
                }
                cmd.add(url)
                cmd.add(targetPath)

                val result = runCommand(targetDir.parent ?: ".", *cmd.toTypedArray())

                if (result.contains("fatal:")) {
                    ExecuteResult("Error", "Clone failed: $result", isError = true)
                } else {
                    ExecuteResult("Cloned $repoName", "Repository cloned to $targetPath\n$result")
                }
            } catch (e: Exception) {
                ExecuteResult("Error", "Clone failed: ${e.message}", isError = true)
            }
        }
    }
}

class GitStatusTool : Tool(
    id = "git_status",
    description = """Show the current git status of a repository — modified files, staged changes, branch name, and commit history.
Use this to understand the state of a project's version control before making changes."""
) {
    override val parameters: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Path to the git repository")
            }
        }
        putJsonArray("required") { add("path") }
    }

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ExecuteResult {
        val path = args["path"] as? String ?: return ExecuteResult("Error", "Missing path", isError = true)

        return withContext(Dispatchers.IO) {
            try {
                val gitDir = findGitRoot(File(path))
                if (gitDir == null) {
                    return@withContext ExecuteResult("Not a git repo", "Directory is not a git repository: $path", isError = true)
                }

                val root = gitDir.parentFile!!.absolutePath
                val status = runCommand(root, "git", "status", "--short")
                val branch = runCommand(root, "git", "rev-parse", "--abbrev-ref", "HEAD").trim()
                val log = runCommand(root, "git", "log", "--oneline", "-10")
                val diff = runCommand(root, "git", "diff", "--stat")

                val result = buildString {
                    appendLine("Branch: $branch")
                    appendLine("Root: $root")
                    appendLine()
                    if (status.isNotBlank()) {
                        appendLine("--- Status ---")
                        appendLine(status)
                    } else {
                        appendLine("Working tree clean")
                    }
                    if (diff.isNotBlank()) {
                        appendLine()
                        appendLine("--- Unstaged Changes ---")
                        appendLine(diff)
                    }
                    appendLine()
                    appendLine("--- Recent Commits ---")
                    appendLine(log)
                }

                ExecuteResult("Git status: $branch", result)
            } catch (e: Exception) {
                ExecuteResult("Error", "Git status failed: ${e.message}", isError = true)
            }
        }
    }
}

class RepoOverviewTool : Tool(
    id = "repo_overview",
    description = """Get a high-level overview of a repository structure.
Lists directory layout, detects programming languages/ecosystems (Node.js, Python, Go, Rust, etc.),
identifies package manager and dependency files, and shows git branch info.
Use this to quickly understand the structure of a project."""
) {
    override val parameters: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Path to the repository or directory to overview")
            }
            putJsonObject("depth") {
                put("type", "number")
                put("description", "Directory exploration depth (1-6, default 3)")
            }
        }
        putJsonArray("required") { add("path") }
    }

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ExecuteResult {
        val path = args["path"] as? String ?: return ExecuteResult("Error", "Missing path", isError = true)
        val depth = (args["depth"] as? Number)?.toInt()?.coerceIn(1, 6) ?: 3

        return withContext(Dispatchers.IO) {
            try {
                val root = File(path)
                if (!root.exists()) return@withContext ExecuteResult("Error", "Directory not found: $path", isError = true)

                val gitRoot = findGitRoot(root)

                // Detect ecosystems
                val ecosystems = detectEcosystems(root)
                val packageManager = detectPackageManager(root)
                val entrypoints = findEntrypoints(root)

                // Directory tree
                val tree = buildDirectoryTree(root, root, depth, 0, mutableSetOf())

                // Git info
                var gitInfo = ""
                if (gitRoot != null) {
                    val branch = try { runCommand(gitRoot.parentFile!!.absolutePath, "git", "rev-parse", "--abbrev-ref", "HEAD").trim() } catch (_: Exception) { "unknown" }
                    val head = try { runCommand(gitRoot.parentFile!!.absolutePath, "git", "rev-parse", "--short", "HEAD").trim() } catch (_: Exception) { "unknown" }
                    gitInfo = "\nBranch: $branch\nHead: $head\n"
                }

                val result = buildString {
                    appendLine("Repository: ${root.name}")
                    appendLine("Path: ${root.absolutePath}")
                    if (gitRoot != null) {
                        appendLine("Git: yes")
                        append(gitInfo)
                    } else {
                        appendLine("Git: no")
                    }
                    appendLine()
                    if (ecosystems.isNotEmpty()) appendLine("Ecosystems: ${ecosystems.joinToString(", ")}")
                    if (packageManager != null) appendLine("Package Manager: $packageManager")
                    if (entrypoints.isNotEmpty()) appendLine("Entrypoints: ${entrypoints.joinToString(", ")}")
                    appendLine()
                    appendLine("--- Directory Structure ---")
                    append(tree)
                }

                ExecuteResult("Overview: ${root.name}", result)
            } catch (e: Exception) {
                ExecuteResult("Error", "Overview failed: ${e.message}", isError = true)
            }
        }
    }

    private fun buildDirectoryTree(root: File, dir: File, maxDepth: Int, currentDepth: Int, visited: MutableSet<String>): String {
        if (currentDepth > maxDepth) return ""

        val indent = "  ".repeat(currentDepth)
        val sb = StringBuilder()
        val canonical = dir.canonicalPath
        if (canonical in visited) return ""
        visited.add(canonical)

        val files = dir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: return sb.toString()

        val skipDirs = setOf(".git", "node_modules", "__pycache__", ".venv", "venv", "dist", "build", ".next", "target", "vendor", ".gradle", "bazel-out", ".idea", ".nx", ".cache")

        files.forEach { file ->
            if (file.name in skipDirs) return@forEach
            if (file.name.startsWith(".") && !file.isDirectory) return@forEach

            if (file.isDirectory) {
                sb.appendLine("${indent}📁 ${file.name}/")
                sb.append(buildDirectoryTree(root, file, maxDepth, currentDepth + 1, visited))
            } else {
                sb.appendLine("${indent}📄 ${file.name}")
            }
        }

        return sb.toString()
    }

    private fun detectEcosystems(dir: File): List<String> {
        val ecosystems = mutableListOf<String>()
        val files = allFiles(dir, 3)

        if (files.any { it.name == "package.json" }) ecosystems.add("Node.js/JavaScript")
        if (files.any { it.name == "Cargo.toml" }) ecosystems.add("Rust")
        if (files.any { it.name == "go.mod" }) ecosystems.add("Go")
        if (files.any { it.name == "pom.xml" || it.name == "build.gradle.kts" || it.name == "build.gradle" }) ecosystems.add("Java/Kotlin")
        if (files.any { it.name == "requirements.txt" || it.name == "pyproject.toml" || it.name == "setup.py" }) ecosystems.add("Python")
        if (files.any { it.name == "Gemfile" }) ecosystems.add("Ruby")
        if (files.any { it.name == "composer.json" }) ecosystems.add("PHP")
        if (files.any { it.name == "CMakeLists.txt" }) ecosystems.add("C/C++")
        if (files.any { it.name == "pubspec.yaml" }) ecosystems.add("Dart/Flutter")

        return ecosystems
    }

    private fun detectPackageManager(dir: File): String? {
        val files = allFiles(dir, 2).map { it.name }.toSet()
        return when {
            "bun.lock" in files || "bun.lockb" in files -> "bun"
            "pnpm-lock.yaml" in files -> "pnpm"
            "yarn.lock" in files -> "yarn"
            "package-lock.json" in files -> "npm"
            "Cargo.lock" in files -> "cargo"
            "go.sum" in files -> "go"
            "Gemfile.lock" in files -> "bundler"
            else -> null
        }
    }

    private fun findEntrypoints(dir: File): List<String> {
        val entries = mutableListOf<String>()
        val pkgJson = File(dir, "package.json")
        if (pkgJson.exists()) {
            try {
                val parsed = Json { ignoreUnknownKeys = true }.parseToJsonElement(pkgJson.readText()).jsonObject
                for (field in listOf("main", "module", "types", "bin")) {
                    parsed[field]?.jsonPrimitive?.contentOrNull?.let { entries.add("$field: $it") }
                }
                parsed["exports"]?.let { entries.add("exports: <defined>") }
            } catch (_: Exception) {}
        }
        for (entry in listOf("index.js", "index.ts", "main.py", "main.go", "lib.rs", "main.rs", "app.js", "app.ts", "index.html")) {
            if (File(dir, entry).exists()) entries.add(entry)
        }
        return entries
    }

    private fun allFiles(dir: File, maxDepth: Int): List<File> {
        val result = mutableListOf<File>()
        dir.walkTopDown().maxDepth(maxDepth).forEach { file ->
            if (!file.name.startsWith(".") && file.name != "node_modules") result.add(file)
        }
        return result
    }
}

// Git helper used by all git tools
suspend fun runCommand(workdir: String, vararg command: String): String = withContext(Dispatchers.IO) {
    try {
        val pb = ProcessBuilder(*command)
            .directory(File(workdir))
            .redirectErrorStream(true)
        val process = pb.start()
        process.inputStream.bufferedReader().readText()
    } catch (e: Exception) {
        "Error: ${e.message}"
    }
}

fun findGitRoot(dir: File): File? {
    var current = dir.canonicalFile
    while (current != null) {
        val gitDir = File(current, ".git")
        if (gitDir.exists()) return gitDir
        current = current.parentFile ?: return null
    }
    return null
}
