package com.schbrongx.espressotimer.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.schbrongx.espressotimer.DEFAULT_LANGUAGE
import com.schbrongx.espressotimer.DEFAULT_TARGET_TIME
import com.schbrongx.espressotimer.EspressoTimerApp
import com.schbrongx.espressotimer.EspressoTimerMaterialTheme
import com.schbrongx.espressotimer.R
import com.schbrongx.espressotimer.data.SettingsDataStore
import com.schbrongx.espressotimer.utils.formatTime
import com.schbrongx.espressotimer.utils.localizedStringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Composable function for the timer screen
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerScreen(
  navController: NavController,
  targetTime: Float,
  language: String,
  signalEnabled: Boolean,
  useAIToStartTimer: Boolean,
  onAIStartToggle: (Boolean) -> Unit,
  onTrainingIconClicked: () -> Unit
) {
  var time by remember { mutableFloatStateOf(value = 0F) }
  var isRunning by remember { mutableStateOf(value = false) }
  var showHelp by remember { mutableStateOf(value = false) }
  val context = LocalContext.current

  // Calculate progress based on the elapsed time and target time
  val progress = (time / (targetTime * 1000)).coerceIn(0f, 1f)  // Ensure progress is between 0 and 1
  // Determine the color of the progress bar (red if over target time)
  val progressColor = if (time >= targetTime * 1000) Color.Red else MaterialTheme.colorScheme.primary

  // Prüfen, ob wir in der Preview-Umgebung sind
  val isInPreview = LocalInspectionMode.current

  // TODO: Playing sound is a bit laggy but it works. Maybe find a better solution.
  // MediaPlayer instances for sounds
  val blipSound = if (!isInPreview) MediaPlayer.create(context, R.raw.blip) else null
  val beepSound = if (!isInPreview) MediaPlayer.create(context, R.raw.beep) else null

  // Flags für das einmalige Abspielen der Sounds
  var blip2SecPlayed by remember { mutableStateOf(value = false) }
  var blip1SecPlayed by remember { mutableStateOf(value = false) }
  var beepPlayed by remember { mutableStateOf(value = false) }

  // Ein separater CoroutineScope für das Abspielen der Sounds
  val coroutineScope = rememberCoroutineScope()

  // Launch an effect when isRunning changes
  LaunchedEffect(isRunning) {
    while (isRunning) {
      delay(timeMillis = 100L)
      time += 100
      if (signalEnabled && !isInPreview) {
        // Blip bei 2 Sekunden Restzeit
        if (targetTime * 1000 - time <= 2000 && targetTime > 2 && !blip2SecPlayed) {
          coroutineScope.launch { blipSound?.start() }
          blip2SecPlayed = true
        }
        // Blip bei 1 Sekunde Restzeit
        if (targetTime * 1000 - time <= 1000 && targetTime > 1 && !blip1SecPlayed) {
          coroutineScope.launch { blipSound?.start() }
          blip1SecPlayed = true
        }
        // Beep am Ende der Zeit
        if (time >= targetTime * 1000 && !beepPlayed) {
          coroutineScope.launch { beepSound?.start() }
          beepPlayed = true
        }
      }
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(title = { Text(text = localizedStringResource(language, R.string.app_name)) })
    }
  ) { innerPadding ->

    Column(
      verticalArrangement = Arrangement.Top,
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)
        .padding(innerPadding)
    ) {

      // Spacer to push content towards the center
      Spacer(modifier = Modifier.weight(1f))

      // Display the formatted timer
      Text(
        text = formatTime(time = time),
        fontSize = 120.sp,
        modifier = Modifier.align(Alignment.CenterHorizontally)
      )

      Spacer(modifier = Modifier.height(4.dp))

      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp)
      ) {

        // Progress bar showing the elapsed time relative to the target time
        LinearProgressIndicator(
          progress = { progress },
          modifier = Modifier
            .fillMaxWidth()
            .height(16.dp),
          color = progressColor,
          trackColor = progressColor.copy(alpha = 0.3f),
        )

        // Text showing the target time, placed at the end of the progress bar
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.Center),
        ) {
          Text(
            text = targetTime.toString(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
              .align(Alignment.CenterEnd)
              .padding(end = 12.dp),
          )
        }
      }

      // Play/Pause Icon to control the timer
      Icon(
        imageVector = if (isRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
        contentDescription = if (isRunning) localizedStringResource(
          language,
          R.string.pause
        ) else localizedStringResource(language, R.string.play),
        modifier = Modifier
          .size(120.dp)
          .padding(top = 16.dp)
          .align(Alignment.CenterHorizontally)
          .clickable { isRunning = !isRunning }
      )
      Row (
        verticalAlignment = Alignment.CenterVertically
      ){
        Checkbox(
          checked = useAIToStartTimer,
          onCheckedChange = { checked -> onAIStartToggle(checked) }
        )
        Text(text = "Use AI to start timer")
      }


      // Spacer to adjust the layout
      Spacer(modifier = Modifier.weight(2f))

      // Row of icons for additional actions
      Row(
        horizontalArrangement = Arrangement.SpaceAround,
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp)
      ) {
        // Reset icon
        Icon(
          imageVector = Icons.Rounded.Refresh,
          contentDescription = localizedStringResource(language, R.string.reset),
          modifier = Modifier
            .size(48.dp)
            .weight(1f)
            .clickable {
              time = 0F
              isRunning = false
              blip1SecPlayed = false
              blip2SecPlayed = false
              beepPlayed = false
            }
        )
        // Training icon (placeholder for future functionality)
        Icon(
          imageVector = Icons.Rounded.Psychology,
          contentDescription = localizedStringResource(language, R.string.training),
          modifier = Modifier
            .size(48.dp)
            .weight(1f)
            .clickable { /* TODO: Navigate to Training screen */ }
        )
        // Settings icon to navigate to settings screen
        Icon(
          imageVector = Icons.Rounded.Settings,
          contentDescription = localizedStringResource(language, R.string.settings),
          modifier = Modifier
            .size(48.dp)
            .weight(1f)
            .clickable {
              navController.navigate(route = "settings")
            }
        )
        // Help icon to show help dialog
        Icon(
          imageVector = Icons.AutoMirrored.Rounded.Help,
          contentDescription = localizedStringResource(language, R.string.help),
          modifier = Modifier
            .size(48.dp)
            .weight(1f)
            .clickable { showHelp = true }
        )
      }
      // Display the help dialog if showHelp is true
      if (showHelp) {
        AlertDialog(
          onDismissRequest = { showHelp = false },
          title = { Text(text = localizedStringResource(language, R.string.help_title)) },
          text = {
            Text(
              text = localizedStringResource(language, R.string.help_text)
            )
          },
          confirmButton = {
            Button(onClick = { showHelp = false }) {
              Text(text = localizedStringResource(language, R.string.close))
            }
          }
        )
      }
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
      settingsDataStore = SettingsDataStore(LocalContext.current),
      savedSignalEnabled = true
    )
  }
}