package com.watchcoach.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.watchcoach.app.ui.ble.BleScannerScreen
import com.watchcoach.app.ui.dashboard.DashboardScreen
import com.watchcoach.app.ui.gestures.GestureLabScreen
import com.watchcoach.app.ui.insights.InsightsScreen
import com.watchcoach.app.ui.insights.WeeklyReportScreen
import com.watchcoach.app.ui.log.RawLogScreen
import com.watchcoach.app.ui.settings.SettingsScreen
import com.watchcoach.app.ui.sync.WatchSyncScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("dashboard", "Dashboard", Icons.Filled.Home),
    Tab("insights", "Insights", Icons.Filled.Insights),
    Tab("gestures", "Gestures", Icons.Filled.TouchApp),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun WatchCoachApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val selectedTab = when (current) {
        "insights", "weekly" -> "insights"
        "gestures" -> "gestures"
        "settings", "ble", "log" -> "settings"
        else -> "dashboard"
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab.route,
                        onClick = { navController.navigateTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding),
        ) {
            composable("dashboard") {
                DashboardScreen(onOpenSyncDetails = { navController.navigate("sync") })
            }
            composable("insights") {
                InsightsScreen(onOpenWeekly = { navController.navigate("weekly") })
            }
            composable("gestures") { GestureLabScreen() }
            composable("settings") {
                SettingsScreen(
                    onOpenScanner = { navController.navigate("ble") },
                    onOpenLog = { navController.navigate("log") },
                )
            }
            composable("weekly") {
                WeeklyReportScreen(onBack = { navController.popBackStack() })
            }
            composable("sync") {
                WatchSyncScreen(
                    onBack = { navController.popBackStack() },
                    onOpenRawLog = { navController.navigate("log") },
                    onOpenScanner = { navController.navigate("ble") },
                )
            }
            composable("ble") {
                BleScannerScreen(onBack = { navController.popBackStack() })
            }
            composable("log") {
                RawLogScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
