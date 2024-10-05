package com.schbrongx.espressotimer.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.schbrongx.espressotimer.R
import com.schbrongx.espressotimer.utils.localizedStringResource

@Composable
fun LanguageDropdown(
    currentLanguage: String,
    selectedLanguageCode: String,
    onLanguageSelected: (String) -> Unit,
    availableLanguages: List<Pair<String, String>>
) {
    // Label
    Text(
        text = localizedStringResource(currentLanguage, R.string.language),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )

    // State to control the dropdown menu expansion
    var expanded by remember { mutableStateOf(value=false) }

    // Get the display name for the selected language code
    val selectedLanguageName = availableLanguages.firstOrNull { it.first == selectedLanguageCode }?.second ?: ""

    // Outlined box to mimic TextField
    Box(
        modifier = Modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                shape = MaterialTheme.shapes.extraSmall
            )
            .padding(horizontal = 8.dp)
            .clickable { expanded = !expanded }  // Click to expand/collapse the dropdown
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Display current selected language
            Text(
                text = selectedLanguageName,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            // Dropdown arrow icon
            Icon(
                imageVector = if (expanded) Icons.Rounded.ArrowDropUp else Icons.Rounded.ArrowDropDown,
                contentDescription = localizedStringResource(currentLanguage, R.string.language),
            )
        }

        // DropdownMenu
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            availableLanguages.forEach { (languageCode, languageName) ->
                DropdownMenuItem(
                    text = { Text(text = languageName) },
                    onClick = {
                        onLanguageSelected(languageCode)
                        expanded = false
                    }
                )
            }
        }
    }
}