package com.lmstudio.client.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lmstudio.client.data.preferences.AppPreferences
import com.lmstudio.client.data.repository.ChatRepository
import com.lmstudio.client.ui.chat.ChatScreen
import com.lmstudio.client.ui.chat.ChatViewModel
import com.lmstudio.client.ui.settings.SettingsScreen
import com.lmstudio.client.ui.settings.SettingsViewModel

private const val ROUTE_CHAT = "chat"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
    preferences: AppPreferences,
    chatRepository: ChatRepository
) {
    // Keep a single ChatViewModel instance so the conversation survives navigation
    val chatViewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.Factory(chatRepository, preferences)
    )

    NavHost(navController = navController, startDestination = ROUTE_CHAT) {
        composable(ROUTE_CHAT) {
            ChatScreen(
                viewModel = chatViewModel,
                onNavigateToSettings = { navController.navigate(ROUTE_SETTINGS) }
            )
        }
        composable(ROUTE_SETTINGS) {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(preferences)
            )
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                    chatViewModel.loadModels() // Reload model list after URL may have changed
                }
            )
        }
    }
}
