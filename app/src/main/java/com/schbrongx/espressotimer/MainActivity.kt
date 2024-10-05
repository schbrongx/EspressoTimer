package com.schbrongx.espressotimer

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.schbrongx.espressotimer.data.SettingsDataStore
import com.schbrongx.espressotimer.ui.screens.SettingsScreen
import com.schbrongx.espressotimer.ui.screens.TimerScreen
import kotlinx.coroutines.launch

// Extension property to create a DataStore instance with the name "settings"
val Context.dataStore by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {
  // Instance of SettingsDataStore to manage settings
  private lateinit var settingsDataStore: SettingsDataStore

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Initialize SettingsDataStore with application context
    settingsDataStore = SettingsDataStore(applicationContext)

    lifecycleScope.launch {
      val (savedTargetTime, savedLanguage, savedSignalEnabled) = settingsDataStore.getSavedSettings()

      setContent {
        val isInitialized = remember { mutableStateOf(value = false) }

        LaunchedEffect(Unit) {
          // Make sure that the initial values are set only after retrieving data from DataStore
          isInitialized.value = true
        }

        if (isInitialized.value) {
          EspressoTimerMaterialTheme {
            EspressoTimerApp(
              savedTargetTime = savedTargetTime,
              savedLanguage = savedLanguage,
              savedSignalEnabled = savedSignalEnabled,
              settingsDataStore = settingsDataStore // Pass the SettingsDataStore to the app
            )
          }
        } else {
          // Show loading UI while settings are being loaded
          LoadingScreen()
        }
      }
    }
  }
}

@Composable
fun LoadingScreen() {
  // A simple loading screen while settings are being loaded
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator()
  }
}

// Composable function for the main application
@Composable
fun EspressoTimerApp(
  savedTargetTime: Float,
  savedLanguage: String,
  savedSignalEnabled: Boolean,
  settingsDataStore: SettingsDataStore
) {
  // Remember the NavController for navigation between screens
  val navController = rememberNavController()
  // Mutable state for targetTime and language
  var targetTime by remember { mutableFloatStateOf(value = savedTargetTime) }
  var language by remember { mutableStateOf(value = savedLanguage) }
  var signalEnabled by remember { mutableStateOf(value = savedSignalEnabled) }
  val coroutineScope = rememberCoroutineScope() // obtain a CoroutineScope to be able to save settings

  // Set up the navigation host
  NavHost(navController = navController, startDestination = "timer") {
    // Timer screen route
    composable(route = "timer") {
      TimerScreen(navController, targetTime, language, signalEnabled)
    }
    // Settings screen route
    composable(route = "settings") {
      SettingsScreen(
        initialTargetTime = targetTime,
        initialLanguage = language,
        initialSignalEnabled = signalEnabled,
        onClose = { navController.popBackStack() },
        onSave = { newTargetTime, newLanguage, newSignalEnabled ->
          targetTime = newTargetTime
          language = newLanguage
          signalEnabled = newSignalEnabled
          coroutineScope.launch {
            settingsDataStore.saveSettings(newTargetTime, newLanguage, newSignalEnabled)
          }
        }
      )
    }
  }
}

@Preview(showBackground = true)
@Composable
fun TimerScreenPreview() {
  EspressoTimerMaterialTheme {
    EspressoTimerApp(
      savedTargetTime = DEFAULT_TARGET_TIME,
      savedLanguage = DEFAULT_LANGUAGE,
      savedSignalEnabled = DEFAULT_SIGNAL_ENABLED,
      settingsDataStore = SettingsDataStore(LocalContext.current)
    )
  }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
  EspressoTimerMaterialTheme {
    SettingsScreen(
      onClose = {},
      onSave = { _, _, _ -> },
      initialTargetTime = DEFAULT_TARGET_TIME,
      initialLanguage = DEFAULT_LANGUAGE,
      initialSignalEnabled = true,
    )
  }
}

@Composable
fun EspressoTimerMaterialTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit
) {
  // Define color schemes for light and dark themes
  val colorScheme = when {
    darkTheme -> darkColorScheme(
      primary = Color(color = 0xFF4A2C2A),
      onPrimary = Color(color = 0xFFFFFFFF),
      onSurface = Color(color = 0xFFFFFFFF)
    )
    else -> lightColorScheme(
      primary = Color(color = 0xFF6F4E37),
      onSurface = Color(color = 0xFF4A2C2A)
    )
  }
  val view = LocalView.current
  if (!view.isInEditMode) {
    // SideEffect to modify the status bar color and appearance
    SideEffect {
      val window = (view.context as Activity).window
      window.statusBarColor = colorScheme.primary.toArgb()
      val windowInsetsController = WindowCompat.getInsetsController(window, view)
      // Set the status bar icons to dark or light based on the theme
      windowInsetsController.isAppearanceLightStatusBars = !darkTheme
    }
  }

  // Apply the color scheme to the MaterialTheme
  MaterialTheme(
    colorScheme = colorScheme,
    content = content
  )
}