package com.opencode.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector? = null) {
    data object Home : Screen("home", "Home", Icons.Default.Home)
    data object Chat : Screen("chat", "Chat", Icons.Default.Chat)
    data object Files : Screen("files", "Files", Icons.Default.Folder)
    data object Editor : Screen("editor/{filePath}", "Editor") {
        fun createRoute(filePath: String) = "editor/${java.net.URLEncoder.encode(filePath, "UTF-8")}"
    }
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    data object ApiConfig : Screen("api_config", "API Configuration")
}
