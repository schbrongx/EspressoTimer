package com.schbrongx.espressotimer.utils

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import java.util.Locale

fun formatTime(time: Float): String {
    val totalSeconds = time / 1000.0
    return String.format(Locale.getDefault(), format = "%04.1f", totalSeconds)  // 0 = leading zero, 4 = total digits, .1f = one decimal place
}

@Composable
fun localizedStringResource(language: String, resId: Int): String {
    val context = LocalContext.current
    val isInPreview = LocalInspectionMode.current

    if (isInPreview) {
        // In preview mode, use the default stringResource
        return stringResource(resId)
    } else {
        // Create a locale based on the provided language
        val locale = Locale(language)
        // Duplicate the current configuration to prevent modifying the global config
        val configuration = Configuration(context.resources.configuration)
        // Set the desired locale
        configuration.setLocale(locale)
        // Create a localized context
        val localizedContext = context.createConfigurationContext(configuration)
        return localizedContext.resources.getString(resId)
    }
}