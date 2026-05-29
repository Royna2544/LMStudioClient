package com.lmstudio.client.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lmstudio.client.R
import kotlin.math.round
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsSheet(
    settings: ChatSettings,
    onSettingsChange: (ChatSettings) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Text(stringResource(R.string.chat_settings), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // System prompt
            Text(stringResource(R.string.system_prompt), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = settings.systemPrompt,
                onValueChange = { onSettingsChange(settings.copy(systemPrompt = it)) },
                placeholder = { Text(stringResource(R.string.optional_system_message)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // Stream toggle
            SettingRowToggle(
                label = stringResource(R.string.streaming),
                checked = settings.stream,
                onCheckedChange = { onSettingsChange(settings.copy(stream = it)) }
            )

            SettingRowToggle(
                label = stringResource(R.string.save_chat_history_lm_studio),
                checked = settings.saveRemoteHistory,
                onCheckedChange = { onSettingsChange(settings.copy(saveRemoteHistory = it)) }
            )

            Spacer(Modifier.height(8.dp))

            // Temperature
            SliderSetting(
                label = stringResource(R.string.temperature),
                value = settings.temperature.clampTemperature(),
                valueRange = MIN_TEMPERATURE..MAX_TEMPERATURE,
                steps = 9,
                displayValue = "%.1f".format(settings.temperature.clampTemperature()),
                onValueChange = { onSettingsChange(settings.copy(temperature = it.roundToTenths().clampTemperature())) }
            )

            // Top-P
            SliderSetting(
                label = stringResource(R.string.top_p),
                value = settings.topP,
                valueRange = 0f..1f,
                steps = 99,
                displayValue = "%.2f".format(settings.topP),
                onValueChange = { onSettingsChange(settings.copy(topP = it.roundToHundredths())) }
            )

            // Top-K (integer)
            SliderSetting(
                label = stringResource(R.string.top_k),
                value = settings.topK.toFloat(),
                valueRange = 1f..200f,
                steps = 198,
                displayValue = "${settings.topK}",
                onValueChange = { onSettingsChange(settings.copy(topK = it.roundToInt())) }
            )

            // Min-P
            SliderSetting(
                label = stringResource(R.string.min_p),
                value = settings.minP,
                valueRange = 0f..1f,
                steps = 99,
                displayValue = "%.2f".format(settings.minP),
                onValueChange = { onSettingsChange(settings.copy(minP = it.roundToHundredths())) }
            )

            Spacer(Modifier.height(4.dp))

            // Repeat penalty toggle
            SettingRowToggle(
                label = stringResource(R.string.repeat_penalty),
                checked = settings.repeatPenaltyEnabled,
                onCheckedChange = { onSettingsChange(settings.copy(repeatPenaltyEnabled = it)) }
            )

            SliderSetting(
                label = stringResource(R.string.penalty_ratio),
                value = settings.repeatPenalty,
                valueRange = 1f..2f,
                steps = 19,
                displayValue = "%.2f".format(settings.repeatPenalty),
                onValueChange = { onSettingsChange(settings.copy(repeatPenalty = it.roundToTwentieths())) }
            )

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // Reasoning mode
            Text(stringResource(R.string.reasoning_mode), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ReasoningMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.reasoningMode == mode,
                        onClick = { onSettingsChange(settings.copy(reasoningMode = mode)) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ReasoningMode.entries.size
                        ),
                        label = { Text(mode.labelText()) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReasoningMode.labelText(): String =
    when (this) {
        ReasoningMode.OFF -> stringResource(R.string.reasoning_off)
        ReasoningMode.AUTO -> stringResource(R.string.reasoning_auto)
        ReasoningMode.ON -> stringResource(R.string.reasoning_on)
    }

private fun Float.roundToTenths(): Float = roundToPlaces(10f)

private fun Float.roundToHundredths(): Float = roundToPlaces(100f)

private fun Float.roundToTwentieths(): Float = roundToPlaces(20f)

private fun Float.roundToPlaces(scale: Float): Float = round(this * scale) / scale

@Composable
private fun SettingRowToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    steps: Int = 0,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(displayValue, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
