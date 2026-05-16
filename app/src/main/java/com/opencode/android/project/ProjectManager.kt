package com.opencode.android.project

import com.opencode.android.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

class ProjectManager {
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _currentProjectId = MutableStateFlow<String?>(null)
    val currentProjectId: StateFlow<String?> = _currentProjectId.asStateFlow()

    fun addProject(rootPath: String): Project {
        val file = File(rootPath)
        val project = Project(
            id = generateId(),
            name = file.name,
            rootPath = file.absolutePath
        )
        _projects.value = _projects.value + project
        return project
    }

    fun removeProject(id: String) {
        _projects.value = _projects.value.filter { it.id != id }
        if (_currentProjectId.value == id) {
            _currentProjectId.value = _projects.value.firstOrNull()?.id
        }
    }

    fun getProject(id: String): Project? = _projects.value.find { it.id == id }

    fun getCurrentProject(): Project? = _currentProjectId.value?.let { getProject(it) }

    fun setCurrentProject(id: String) {
        _currentProjectId.value = id
    }

    fun getFiles(projectId: String, path: String = ""): List<FileEntry> {
        val project = getProject(projectId) ?: return emptyList()
        val rootDir = File(project.rootPath, path)
        if (!rootDir.exists() || !rootDir.isDirectory) return emptyList()

        val files = rootDir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: return emptyList()

        return files.map { file ->
            FileEntry(
                name = file.name,
                path = file.absolutePath,
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else null,
                lastModified = file.lastModified()
            )
        }
    }

    fun getFileTree(projectId: String, path: String = "", depth: Int = 0, maxDepth: Int = 3): List<FileEntry> {
        if (depth > maxDepth) return emptyList()
        val entries = getFiles(projectId, path)
        return entries.map { entry ->
            if (entry.isDirectory && depth < maxDepth) {
                entry.copy(children = getFileTree(projectId, entry.path, depth + 1, maxDepth))
            } else entry
        }
    }

    private fun generateId(): String {
        return "proj_${UUID.randomUUID().toString().take(8)}"
    }

    data class FileEntry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long? = null,
        val lastModified: Long = 0,
        val children: List<FileEntry> = emptyList()
    )

    companion object {
        @Volatile
        private var instance: ProjectManager? = null

        fun getInstance(): ProjectManager {
            return instance ?: synchronized(this) {
                instance ?: ProjectManager().also { instance = it }
            }
        }
    }
}
