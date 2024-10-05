package com.schbrongx.espressotimer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.schbrongx.espressotimer.DEFAULT_LANGUAGE
import com.schbrongx.espressotimer.DEFAULT_TARGET_TIME
import com.schbrongx.espressotimer.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

// Class to manage saving and loading settings using DataStore
class SettingsDataStore(private val context: Context) {

    companion object {
        // Preference keys for target time and language
        val TARGET_TIME_KEY = floatPreferencesKey(name = "target_time")
        val LANGUAGE_KEY = stringPreferencesKey(name = "language")
    }

    // Suspend function to save settings (targetTime and language) into DataStore
    suspend fun saveSettings(targetTime: Float, language: String) {
        context.dataStore.edit { preferences ->
            preferences[TARGET_TIME_KEY] = targetTime
            preferences[LANGUAGE_KEY] = language
        }
    }

    // Flow to read the target time from DataStore
    val targetTime: Flow<Float> = context.dataStore.data
        .catch {
            emit(emptyPreferences())
        }
        .map { preferences ->
            // Retrieve the target time, or use default value 21.5f if not set
            preferences[TARGET_TIME_KEY] ?: DEFAULT_TARGET_TIME  // Default target time is 21.5
        }

    // Flow to read the language setting from DataStore
    val language: Flow<String> = context.dataStore.data
        .catch {
            emit(emptyPreferences())
        }
        .map { preferences ->
            // Retrieve the language, or use default value "de" if not set
            preferences[LANGUAGE_KEY] ?: DEFAULT_LANGUAGE  // Default language is "de"
        }
}