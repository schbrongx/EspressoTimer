/* SettingsDataStore.kt */
package com.schbrongx.espressotimer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.schbrongx.espressotimer.DEFAULT_LANGUAGE
import com.schbrongx.espressotimer.DEFAULT_SIGNAL_ENABLED
import com.schbrongx.espressotimer.DEFAULT_TARGET_TIME
import com.schbrongx.espressotimer.DEFAULT_USE_AI_TO_START_TIMER
import com.schbrongx.espressotimer.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map


data class MultiComponent(val first: Float, val second: String, val third: Boolean, val fourth: Boolean)

// Class to manage saving and loading settings using DataStore
class SettingsDataStore(private val context: Context) {

  companion object {
    // Preference keys for target time and language
    val TARGET_TIME_KEY = floatPreferencesKey(name = "target_time")
    val LANGUAGE_KEY = stringPreferencesKey(name = "language")
    val SIGNAL_ENABLED_KEY = booleanPreferencesKey(name = "signal_enabled")
    val USE_AI_TO_START_TIMER_KEY = booleanPreferencesKey(name = "use_ai_to_start_timer")
  }

  // Suspend function to save settings (targetTime and language) into DataStore
  suspend fun getSavedSettings(): MultiComponent {
    val preferences = context.dataStore.data.first()
    val targetTime = preferences[TARGET_TIME_KEY] ?: DEFAULT_TARGET_TIME
    val language = preferences[LANGUAGE_KEY] ?: DEFAULT_LANGUAGE
    val signalEnabled = preferences[SIGNAL_ENABLED_KEY] ?: DEFAULT_SIGNAL_ENABLED
    val useAIToStartTimer = preferences[USE_AI_TO_START_TIMER_KEY] ?: false
    return MultiComponent(targetTime, language, signalEnabled, useAIToStartTimer)
  }

  // Suspend function to save settings (targetTime and language) into DataStore
  suspend fun saveSettings(targetTime: Float, language: String, signalEnabled: Boolean, useAIToStartTimer: Boolean) {
    context.dataStore.edit { preferences ->
      preferences[TARGET_TIME_KEY] = targetTime
      preferences[LANGUAGE_KEY] = language
      preferences[SIGNAL_ENABLED_KEY] = signalEnabled
      preferences[USE_AI_TO_START_TIMER_KEY] = useAIToStartTimer
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

  // Flow to read the signal enabled setting from DataStore
  val signalEnabled: Flow<Boolean> = context.dataStore.data
    .catch { emit(emptyPreferences()) }
    .map { preferences ->
      preferences[SIGNAL_ENABLED_KEY] ?: DEFAULT_SIGNAL_ENABLED
    }

  // FLow to read the use AI to start timer setting from DataStore
  val useAIToStartTimer : Flow<Boolean> = context.dataStore.data
    .catch { emit(emptyPreferences()) }
    .map { preferences ->
      // Retreive the use AI to start timer, or use default value false if not set
      preferences[USE_AI_TO_START_TIMER_KEY] ?: DEFAULT_USE_AI_TO_START_TIMER
    }
}

