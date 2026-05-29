package com.lmstudio.client.ui.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lmstudio.client.R
import com.lmstudio.client.data.preferences.AppPreferences
import com.lmstudio.client.data.preferences.SearchProvider
import com.lmstudio.client.ui.chat.LOCAL_TOOL_INFOS
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var tokenVisible by remember { mutableStateOf(false) }
    var braveKeyVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_cd)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.lm_studio_connection),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = uiState.baseUrl,
                onValueChange = { viewModel.updateBaseUrl(it) },
                label = { Text(stringResource(R.string.base_url)) },
                placeholder = { Text(stringResource(R.string.base_url_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text(stringResource(R.string.base_url_help))
                }
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = uiState.bearerToken,
                onValueChange = { viewModel.updateBearerToken(it) },
                label = { Text(stringResource(R.string.bearer_token)) },
                placeholder = { Text(stringResource(R.string.optional_token_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            imageVector = if (tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (tokenVisible) {
                                stringResource(R.string.hide_token_cd)
                            } else {
                                stringResource(R.string.show_token_cd)
                            }
                        )
                    }
                },
                supportingText = { Text(stringResource(R.string.bearer_token_help)) }
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.web_search),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.web_search_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            SearchProviderPicker(
                selectedProvider = uiState.searchProvider,
                onProviderSelected = viewModel::updateSearchProvider
            )
            if (uiState.searchProvider == SearchProvider.BRAVE) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = uiState.braveSearchApiKey,
                    onValueChange = viewModel::updateBraveSearchApiKey,
                    label = { Text(stringResource(R.string.brave_search_api_key)) },
                    placeholder = { Text(stringResource(R.string.brave_search_api_key_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (braveKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { braveKeyVisible = !braveKeyVisible }) {
                            Icon(
                                imageVector = if (braveKeyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (braveKeyVisible) {
                                    stringResource(R.string.hide_api_key_cd)
                                } else {
                                    stringResource(R.string.show_api_key_cd)
                                }
                            )
                        }
                    },
                    supportingText = { Text(stringResource(R.string.brave_search_help)) }
                )
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.chat_ui),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            LocalToolToggleRow(
                name = stringResource(R.string.fold_thinking_by_default),
                description = stringResource(R.string.fold_thinking_by_default_help),
                checked = uiState.foldThinkingByDefault,
                onCheckedChange = viewModel::updateFoldThinkingByDefault
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.local_mcp_tools),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.local_mcp_tools_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            LocalToolRoundsControl(
                rounds = uiState.localToolRounds,
                onRoundsChange = viewModel::updateLocalToolRounds
            )
            Spacer(Modifier.height(8.dp))
            LOCAL_TOOL_INFOS.forEach { tool ->
                LocalToolToggleRow(
                    name = tool.name,
                    description = tool.description,
                    checked = tool.name in uiState.enabledLocalTools,
                    onCheckedChange = { viewModel.updateLocalToolEnabled(tool.name, it) }
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.saveAndClose(onNavigateBack) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.save_and_go_back))
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.setup_checklist),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.setup_checklist_steps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SearchProviderPicker(
    selectedProvider: SearchProvider,
    onProviderSelected: (SearchProvider) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(selectedProvider.labelText())
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            SearchProvider.entries.forEach { provider ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(provider.labelText())
                            Text(
                                text = provider.descriptionText(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onProviderSelected(provider)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SearchProvider.labelText(): String =
    when (this) {
        SearchProvider.DISABLED -> stringResource(R.string.search_provider_disabled)
        SearchProvider.BRAVE -> stringResource(R.string.search_provider_brave)
    }

@Composable
private fun SearchProvider.descriptionText(): String =
    when (this) {
        SearchProvider.DISABLED -> stringResource(R.string.search_provider_disabled_help)
        SearchProvider.BRAVE -> stringResource(R.string.search_provider_brave_help)
    }

@Composable
private fun LocalToolRoundsControl(
    rounds: Int,
    onRoundsChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.max_tool_rounds),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.max_tool_rounds_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = rounds.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = rounds.toFloat(),
            onValueChange = { onRoundsChange(it.roundToInt()) },
            valueRange = AppPreferences.MIN_LOCAL_TOOL_ROUNDS.toFloat()..AppPreferences.MAX_LOCAL_TOOL_ROUNDS.toFloat(),
            steps = AppPreferences.MAX_LOCAL_TOOL_ROUNDS - AppPreferences.MIN_LOCAL_TOOL_ROUNDS - 1
        )
    }
}

@Composable
private fun LocalToolToggleRow(
    name: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
