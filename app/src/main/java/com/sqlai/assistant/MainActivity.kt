package com.sqlai.assistant

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.service.ListeningService
import com.sqlai.assistant.ui.ApiConfigScreen
import com.sqlai.assistant.ui.DashboardScreen
import com.sqlai.assistant.ui.PermissionsScreen
import com.sqlai.assistant.ui.PromptScreen
import com.sqlai.assistant.ui.SettingsScreen
import com.sqlai.assistant.ui.theme.SqlAiTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results.count { it.value }
            LogBus.log("Runtime permissions: $granted/${results.size} granted",
                if (granted == results.size) LogLevel.SUCCESS else LogLevel.WARN)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v5: keep the accessibility/battery watchdog alive while the UI runs.
        com.sqlai.assistant.service.AccessibilityWatchdogService.start(this)
        enableEdgeToEdge()

        requestRuntimePermissions()
        autoStartListener()

        setContent {
            SqlAiTheme {
                AppRoot()
            }
        }
    }

    private fun requestRuntimePermissions() {
        val needed = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            runtimePermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun autoStartListener() {
        uiScope.launch {
            val settings = SqlAiApp.settings.settings.first()
            if (settings.assistantEnabled && settings.listenServiceEnabled) {
                ListeningService.start(this@MainActivity)
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("dashboard", "Dashboard", Icons.Filled.Dashboard),
    Tab("permissions", "Access", Icons.Filled.Security),
    Tab("api", "API", Icons.Filled.Api),
    Tab("prompt", "Prompt", Icons.Filled.TextFields),
    Tab("settings", "Settings", Icons.Filled.Settings)
)

@Composable
private fun AppRoot() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding)
        ) {
            composable("dashboard") { DashboardScreen() }
            composable("permissions") { PermissionsScreen() }
            composable("api") { ApiConfigScreen() }
            composable("prompt") { PromptScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}
