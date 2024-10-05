package com.schbrongx.espressotimer.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.schbrongx.espressotimer.R
import com.schbrongx.espressotimer.utils.localizedStringResource
import java.util.Locale

@Composable
fun NumberStepper(
    language: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    step: Float = 0.5f,
    range: ClosedFloatingPointRange<Float> = 0f..99.5f
) {
    // Label
    Text(
        text = localizedStringResource(language, R.string.target_time),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )

    // Outlined box to mimic TextField
    Box(
        modifier = Modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.extraSmall
            )
            .padding(horizontal = 8.dp)
        //.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Display current value
            Text(
                text = String.format(Locale.getDefault(), format = "%04.1f", value),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.width(10.dp))
            // Increase button
            IconButton(onClick = {
                val newValue = (value + step).coerceIn(range)
                onValueChange(newValue)
            }) {
                Icon(Icons.Default.AddCircleOutline, contentDescription = localizedStringResource(language, R.string.increase))
            }
            // Decrease button
            IconButton(onClick = {
                val newValue = (value - step).coerceIn(range)
                onValueChange(newValue)
            }) {
                Icon(Icons.Default.RemoveCircleOutline, contentDescription = localizedStringResource(language, R.string.decrease))
            }
        }
    }
}