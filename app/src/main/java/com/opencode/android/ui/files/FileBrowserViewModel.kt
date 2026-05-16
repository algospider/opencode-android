package com.opencode.android.ui.files

import androidx.lifecycle.ViewModel
import com.opencode.android.project.ProjectManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FileBrowserUiState(
    val currentPath: String = "",
    val entries: List<ProjectManager.FileEntry> = emptyList(),
    val selectedProjectName: String = "No project",
    val breadcrumbs: List<Breadcrumb> = listOf(Breadcrumb("Root", "")),
    val projectId: String? = null
)

data class Breadcrumb(val name: String, val path: String)

class FileBrowserViewModel : ViewModel() {
    private val projectManager = ProjectManager.getInstance()

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    fun loadProject(projectId: String) {
        val project = projectManager.getProject(projectId) ?: return
        _uiState.value = _uiState.value.copy(
            projectId = projectId,
            selectedProjectName = project.name,
            currentPath = project.rootPath,
            breadcrumbs = listOf(Breadcrumb(project.name, project.rootPath))
        )
        refreshFiles()
    }

    fun navigateTo(path: String, name: String) {
        val crumbs = _uiState.value.breadcrumbs
        val idx = crumbs.indexOfFirst { it.path == path }
        _uiState.value = _uiState.value.copy(
            currentPath = path,
            breadcrumbs = if (idx >= 0) crumbs.take(idx + 1) else crumbs + Breadcrumb(name, path)
        )
        refreshFiles()
    }

    fun navigateUp() {
        val crumbs = _uiState.value.breadcrumbs
        if (crumbs.size <= 1) return
        val newCrumbs = crumbs.dropLast(1)
        _uiState.value = _uiState.value.copy(
            currentPath = newCrumbs.last().path,
            breadcrumbs = newCrumbs
        )
        refreshFiles()
    }

    private fun refreshFiles() {
        val pid = _uiState.value.projectId ?: return
        val path = _uiState.value.currentPath
        val entries = projectManager.getFiles(pid, path)
        _uiState.value = _uiState.value.copy(entries = entries)
    }
}
