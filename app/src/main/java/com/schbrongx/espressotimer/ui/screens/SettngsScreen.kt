package com.schbrongx.espressotimer.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.schbrongx.espressotimer.AVAILABLE_LANGUAGES
import com.schbrongx.espressotimer.DEFAULT_LANGUAGE
import com.schbrongx.espressotimer.DEFAULT_TARGET_TIME
import com.schbrongx.espressotimer.EspressoTimerMaterialTheme
import com.schbrongx.espressotimer.ui.components.LanguageDropdown
import com.schbrongx.espressotimer.ui.components.NumberStepper
import com.schbrongx.espressotimer.R
import com.schbrongx.espressotimer.utils.localizedStringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    initialTargetTime: Float,
    initialLanguage: String,
    initialSignalEnabled: Boolean,
    onClose: () -> Unit,
    onSave: (Float, String, Boolean) -> Unit
) {
    var targetTime by remember { mutableFloatStateOf(initialTargetTime) }
    var language by remember { mutableStateOf(initialLanguage) }
    var signalEnabled by remember { mutableStateOf(initialSignalEnabled) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localizedStringResource(language, R.string.settings)) },
                modifier = Modifier
                    .border(width = 1.dp, color = MaterialTheme.colorScheme.primary),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = localizedStringResource(language, R.string.back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .padding(innerPadding),
            verticalArrangement = Arrangement.Top
        ) {

            // Target Time Number Stepper
            NumberStepper(
                language = language,
                value = targetTime,
                onValueChange = { newValue ->
                    targetTime = newValue
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Language Dropdown Menu
            LanguageDropdown(
                currentLanguage = language,
                selectedLanguageCode = language,
                onLanguageSelected = { newLanguageCode ->
                    language = newLanguageCode
                },
                availableLanguages = AVAILABLE_LANGUAGES
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Signal Enabled Checkbox
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = signalEnabled,
                    onCheckedChange = { signalEnabled = it },
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                )
                Text(
                    text = localizedStringResource(language, R.string.signal_enabled),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Save Button
            Button(
                onClick = {
                    onSave(targetTime, language, signalEnabled)
                    onClose()
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(text = localizedStringResource(language, R.string.save))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    EspressoTimerMaterialTheme {
        SettingsScreen(
            onNavigateBack = {},
            onClose = {},
            onSave = { _, _, _ -> },
            initialTargetTime = DEFAULT_TARGET_TIME,
            initialLanguage = DEFAULT_LANGUAGE,
            initialSignalEnabled = true,
        )
    }
}