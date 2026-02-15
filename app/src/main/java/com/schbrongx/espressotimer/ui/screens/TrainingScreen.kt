package com.schbrongx.espressotimer.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.collectLatest
import com.schbrongx.espressotimer.DEFAULT_LANGUAGE
import com.schbrongx.espressotimer.EspressoTimerMaterialTheme
import com.schbrongx.espressotimer.R
import com.schbrongx.espressotimer.training.AndroidAudioBackend
import com.schbrongx.espressotimer.training.LearnedTriggerComputationResult
import com.schbrongx.espressotimer.training.LearnedTriggerLearner
import com.schbrongx.espressotimer.training.MicrophoneStatus
import com.schbrongx.espressotimer.training.ProfileLearningStatus
import com.schbrongx.espressotimer.training.ProfilesRepository
import com.schbrongx.espressotimer.training.TrainingProfile
import com.schbrongx.espressotimer.training.TrainingSessionManager
import com.schbrongx.espressotimer.training.TrainingSessionUiState
import com.schbrongx.espressotimer.training.TrainingStorage
import com.schbrongx.espressotimer.utils.localizedStringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class TrainingRoute {
  Profiles,
  Session
}

private enum class PrimaryProfileAction {
  StartOrContinueTraining,
  ComputeLearnedTrigger,
  RecomputeLearnedTrigger,
  LearnedUpToDate,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingScreen(onNavigateBack: () -> Unit, language: String) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  val learnedSuccessText = localizedStringResource(language, R.string.training_learned_success)
  val learnedUpToDateText = localizedStringResource(language, R.string.training_learned_up_to_date)

  val storage = remember { TrainingStorage(context.applicationContext) }
  val profilesRepository = remember { ProfilesRepository(storage) }
  val learner = remember { LearnedTriggerLearner(storage, profilesRepository) }
  val sessionManager = remember {
    TrainingSessionManager(
      storage = storage,
      profilesRepository = profilesRepository,
      audioBackend = AndroidAudioBackend(context.applicationContext),
    )
  }

  DisposableEffect(Unit) {
    onDispose {
      sessionManager.close()
    }
  }

  var profilesState by remember { mutableStateOf(profilesRepository.getState()) }
  var selectedSessionProfileId by rememberSaveable { mutableStateOf<String?>(null) }
  var route by rememberSaveable { mutableStateOf(TrainingRoute.Profiles) }
  var createDialogOpen by remember { mutableStateOf(false) }
  var renameProfile by remember { mutableStateOf<TrainingProfile?>(null) }
  var deleteProfile by remember { mutableStateOf<TrainingProfile?>(null) }
  var resetProfile by remember { mutableStateOf<TrainingProfile?>(null) }
  var learningMessage by remember { mutableStateOf<String?>(null) }
  var computingProfileId by remember { mutableStateOf<String?>(null) }

  fun refreshProfiles() {
    profilesState = profilesRepository.getState()
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = if (route == TrainingRoute.Profiles) {
              localizedStringResource(language, R.string.training_learning_mode)
            } else {
              localizedStringResource(language, R.string.training_session_title)
            }
          )
        },
        modifier = Modifier.border(width = 1.dp, color = MaterialTheme.colorScheme.primary),
        navigationIcon = {
          IconButton(onClick = {
            if (route == TrainingRoute.Session) {
              sessionManager.stop()
              route = TrainingRoute.Profiles
              selectedSessionProfileId = null
              refreshProfiles()
            } else {
              onNavigateBack()
            }
          }) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = localizedStringResource(language, R.string.back))
          }
        },
        actions = {
          if (route == TrainingRoute.Profiles) {
            IconButton(onClick = { createDialogOpen = true }) {
              Icon(Icons.Rounded.Add, contentDescription = localizedStringResource(language, R.string.training_new_profile))
            }
          }
        }
      )
    },
  ) { innerPadding ->
    val sessionProfile = profilesState.profiles.firstOrNull { it.id == selectedSessionProfileId }
    LaunchedEffect(route, sessionProfile) {
      if (route == TrainingRoute.Session && sessionProfile == null) {
        route = TrainingRoute.Profiles
        selectedSessionProfileId = null
      }
    }

    if (route == TrainingRoute.Session && sessionProfile != null) {
      SessionScreen(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding)
          .padding(16.dp),
        language = language,
        profile = sessionProfile,
        sessionManager = sessionManager,
        onStop = {
          sessionManager.stop()
          route = TrainingRoute.Profiles
          selectedSessionProfileId = null
          refreshProfiles()
        },
      )
    } else {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding)
          .padding(horizontal = 16.dp, vertical = 12.dp)
      ) {
        if (!learningMessage.isNullOrBlank()) {
          Text(
            text = learningMessage ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(modifier = Modifier.height(8.dp))
        }
        Text(
          text = localizedStringResource(language, R.string.training_guidance_minimum),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (profilesState.profiles.isEmpty()) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text(text = localizedStringResource(language, R.string.training_no_profiles))
              Spacer(modifier = Modifier.height(12.dp))
              Button(onClick = { createDialogOpen = true }) {
                Text(text = localizedStringResource(language, R.string.training_create_first_profile))
              }
            }
          }
        } else {
          LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
          ) {
            items(profilesState.profiles, key = { profile -> profile.id }) { profile ->
              val primaryAction = resolvePrimaryAction(profile)
              ProfileCard(
                language = language,
                profile = profile,
                isActive = profile.id == profilesState.activeProfileId,
                primaryAction = primaryAction,
                onSelect = {
                  profilesRepository.selectActiveProfile(profile.id)
                  refreshProfiles()
                },
                onRename = { renameProfile = profile },
                onDelete = { deleteProfile = profile },
                onReset = { resetProfile = profile },
                isPrimaryActionRunning = computingProfileId == profile.id,
                onStartTraining = {
                  profilesRepository.selectActiveProfile(profile.id)
                  refreshProfiles()
                  selectedSessionProfileId = profile.id
                  route = TrainingRoute.Session
                },
                onPrimaryAction = {
                  when (primaryAction) {
                    PrimaryProfileAction.StartOrContinueTraining -> {
                      profilesRepository.selectActiveProfile(profile.id)
                      refreshProfiles()
                      selectedSessionProfileId = profile.id
                      route = TrainingRoute.Session
                    }

                    PrimaryProfileAction.ComputeLearnedTrigger,
                    PrimaryProfileAction.RecomputeLearnedTrigger -> {
                      if (computingProfileId != null) {
                        return@ProfileCard
                      }
                      computingProfileId = profile.id
                      learningMessage = null
                      coroutineScope.launch {
                        val result = withContext(Dispatchers.Default) {
                          learner.computeLearnedTrigger(profile.id)
                        }
                        refreshProfiles()
                        computingProfileId = null
                        learningMessage = when (result) {
                          is LearnedTriggerComputationResult.Success -> {
                            learnedSuccessText +
                                " (${result.summary.qualityLabel.value}, F1=${String.format("%.3f", result.summary.metrics.f1)})"
                          }

                          is LearnedTriggerComputationResult.Blocked -> result.reason
                          is LearnedTriggerComputationResult.Failure -> result.reason
                        }
                      }
                    }

                    PrimaryProfileAction.LearnedUpToDate -> {
                      learningMessage = learnedUpToDateText
                    }
                  }
                }
              )
            }
          }
        }
      }
    }
  }

  if (createDialogOpen) {
    ProfileNameDialog(
      title = localizedStringResource(language, R.string.training_new_profile),
      confirmText = localizedStringResource(language, R.string.training_create_profile),
      dismissText = localizedStringResource(language, R.string.close),
      initialValue = "",
      onDismiss = { createDialogOpen = false },
      onConfirm = { enteredName ->
        val name = enteredName.trim()
        if (name.isNotEmpty()) {
          profilesRepository.createProfile(name)
          refreshProfiles()
          createDialogOpen = false
        }
      }
    )
  }

  renameProfile?.let { profile ->
    ProfileNameDialog(
      title = localizedStringResource(language, R.string.training_rename_profile),
      confirmText = localizedStringResource(language, R.string.training_rename),
      dismissText = localizedStringResource(language, R.string.close),
      initialValue = profile.name,
      onDismiss = { renameProfile = null },
      onConfirm = { newName ->
        val name = newName.trim()
        if (name.isNotEmpty()) {
          profilesRepository.renameProfile(profile.id, name)
          refreshProfiles()
          renameProfile = null
        }
      }
    )
  }

  deleteProfile?.let { profile ->
    ConfirmDialog(
      title = localizedStringResource(language, R.string.training_delete_profile),
      message = localizedStringResource(language, R.string.training_delete_profile_confirm) + " \"${profile.name}\"?",
      confirmText = localizedStringResource(language, R.string.training_delete),
      dismissText = localizedStringResource(language, R.string.close),
      onDismiss = { deleteProfile = null },
      onConfirm = {
        profilesRepository.deleteProfile(profile.id)
        refreshProfiles()
        deleteProfile = null
      }
    )
  }

  resetProfile?.let { profile ->
    ConfirmDialog(
      title = localizedStringResource(language, R.string.training_reset_data),
      message = localizedStringResource(language, R.string.training_reset_data_confirm) + " \"${profile.name}\"?",
      confirmText = localizedStringResource(language, R.string.training_reset),
      dismissText = localizedStringResource(language, R.string.close),
      onDismiss = { resetProfile = null },
      onConfirm = {
        profilesRepository.resetTrainingData(profile.id)
        refreshProfiles()
        resetProfile = null
      }
    )
  }
}

@Composable
private fun ProfileCard(
  language: String,
  profile: TrainingProfile,
  isActive: Boolean,
  primaryAction: PrimaryProfileAction,
  isPrimaryActionRunning: Boolean,
  onSelect: () -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit,
  onReset: () -> Unit,
  onStartTraining: () -> Unit,
  onPrimaryAction: () -> Unit,
) {
  ElevatedCard(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(text = profile.name, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
          if (isActive) {
            StatusBadge(text = localizedStringResource(language, R.string.training_active), color = MaterialTheme.colorScheme.primary)
          }
          val (statusText, statusColor) = profileStatusBadge(language, profile.learningStatus)
          StatusBadge(text = statusText, color = statusColor)
        }
      }

      Text(text = "${localizedStringResource(language, R.string.training_positives)}: ${profile.positivesCount}")
      Text(text = "${localizedStringResource(language, R.string.training_negatives)}: ${profile.negativesCount}")
      if (!profile.learnedAtIso.isNullOrBlank()) {
        Text(
          text = "${localizedStringResource(language, R.string.training_last_computed)}: ${profile.learnedAtIso}",
          style = MaterialTheme.typography.bodySmall
        )
      }
      if (profile.learnedQualityLabel != null) {
        Text(
          text = "${localizedStringResource(language, R.string.training_quality)}: ${profile.learnedQualityLabel.value}",
          style = MaterialTheme.typography.bodySmall
        )
      }
      Text(
        text = "${localizedStringResource(language, R.string.training_total_recorded)}: ${formatSeconds(profile.totalRecordedSeconds)}",
        style = MaterialTheme.typography.bodySmall
      )
      Text(
        text = "${localizedStringResource(language, R.string.training_last_event)}: ${profile.lastEventWallIso ?: localizedStringResource(language, R.string.training_unknown)}",
        style = MaterialTheme.typography.bodySmall
      )

      Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!isActive) {
          TextButton(onClick = onSelect) {
            Text(text = localizedStringResource(language, R.string.training_set_active))
          }
        }
        if (primaryAction != PrimaryProfileAction.StartOrContinueTraining) {
          TextButton(onClick = onStartTraining) {
            Text(
              text = if (profile.hasData) {
                localizedStringResource(language, R.string.training_continue_training)
              } else {
                localizedStringResource(language, R.string.start_training)
              }
            )
          }
        }
        IconButton(onClick = onRename) {
          Icon(Icons.Rounded.Edit, contentDescription = localizedStringResource(language, R.string.training_rename_profile))
        }
        IconButton(onClick = onDelete) {
          Icon(Icons.Rounded.Delete, contentDescription = localizedStringResource(language, R.string.training_delete_profile))
        }
        IconButton(onClick = onReset, enabled = profile.hasData) {
          Icon(Icons.Rounded.Replay, contentDescription = localizedStringResource(language, R.string.training_reset_data))
        }
      }

      Button(
        onClick = onPrimaryAction,
        modifier = Modifier.fillMaxWidth(),
        enabled = !isPrimaryActionRunning && primaryAction != PrimaryProfileAction.LearnedUpToDate,
      ) {
        val baseText = primaryActionText(language, primaryAction, profile)
        val buttonText = if (isPrimaryActionRunning) "$baseText..." else baseText
        Text(text = buttonText)
      }
    }
  }
}

@Composable
private fun SessionScreen(
  modifier: Modifier,
  language: String,
  profile: TrainingProfile,
  sessionManager: TrainingSessionManager,
  onStop: () -> Unit,
) {
  val context = LocalContext.current
  val view = LocalView.current
  var sessionUiState by remember { mutableStateOf(TrainingSessionUiState()) }
  var detailsExpanded by rememberSaveable(profile.id) { mutableStateOf(false) }
  val microphonePermissionNeededText = localizedStringResource(language, R.string.training_microphone_permission_needed)
  val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) {
      sessionManager.start(profile)
    } else {
      sessionManager.setInfoMessage(microphonePermissionNeededText)
    }
  }

  fun ensureSessionStarted() {
    val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    if (hasPermission) {
      sessionManager.start(profile)
    } else {
      permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  LaunchedEffect(profile.id) {
    ensureSessionStarted()
  }

  LaunchedEffect(sessionManager) {
    sessionManager.uiState.collectLatest { state ->
      sessionUiState = state
    }
  }

  DisposableEffect(sessionUiState.isRunning) {
    view.keepScreenOn = sessionUiState.isRunning
    onDispose {
      view.keepScreenOn = false
    }
  }

  Scaffold(
    modifier = modifier,
    bottomBar = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surface)
          .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Button(
          onClick = { sessionManager.tapShotStart() },
          enabled = sessionUiState.isRunning && sessionUiState.isShotStartReady,
          modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
          Text(
            text = localizedStringResource(language, R.string.training_shot_start),
            style = MaterialTheme.typography.headlineSmall,
          )
        }
        Text(
          text = when {
            sessionUiState.isShotStartBusyPersisting -> localizedStringResource(language, R.string.training_shot_start_waiting)
            sessionUiState.isShotStartReady -> localizedStringResource(language, R.string.training_shot_start_ready)
            else -> localizedStringResource(language, R.string.training_shot_start_buffering)
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          Button(
            onClick = { onStop() },
            modifier = Modifier.weight(1f)
          ) {
            Text(text = localizedStringResource(language, R.string.stop_training))
          }

          Button(
            onClick = { sessionManager.addBackgroundSampleNow() },
            modifier = Modifier.weight(1f),
            enabled = sessionUiState.isRunning,
          ) {
            Text(text = localizedStringResource(language, R.string.training_add_background_now))
          }
        }
      }
    }
  ) { innerPadding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      item {
        Text(text = "${localizedStringResource(language, R.string.training_profile)}: ${profile.name}", style = MaterialTheme.typography.titleMedium)
      }
      item {
        MicStatusBanner(language = language, microphoneStatus = sessionUiState.microphoneStatus)
      }
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Text(text = "${localizedStringResource(language, R.string.training_positives)}: ${sessionUiState.totalPositives}")
          Text(text = "${localizedStringResource(language, R.string.training_negatives)}: ${sessionUiState.totalNegatives}")
        }
      }
      item {
        Text(
          text = "${localizedStringResource(language, R.string.training_ready_in)} ${sessionUiState.missingPositives} / ${sessionUiState.missingNegatives}",
          style = MaterialTheme.typography.bodySmall,
        )
      }
      item {
        if (!sessionUiState.infoMessage.isNullOrBlank()) {
          Text(
            text = sessionUiState.infoMessage ?: "",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
      item {
        TextButton(
          onClick = { detailsExpanded = !detailsExpanded },
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = if (detailsExpanded) {
              localizedStringResource(language, R.string.training_hide_details)
            } else {
              localizedStringResource(language, R.string.training_show_details)
            }
          )
        }
      }
      if (detailsExpanded) {
        item {
          Text(
            text = localizedStringResource(language, R.string.training_guidance_minimum),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
          )
        }
        item {
          Text(text = "${localizedStringResource(language, R.string.training_pending_events)}: ${sessionUiState.pendingPositiveEvents}")
        }
        item {
          Text(text = "${localizedStringResource(language, R.string.training_session_positives)}: ${sessionUiState.sessionPositives}")
        }
        item {
          Text(text = "${localizedStringResource(language, R.string.training_total_recorded)}: ${formatSeconds(sessionUiState.totalRecordedSeconds)}")
        }
        item {
          Text(
            text = "${localizedStringResource(language, R.string.training_last_event)}: ${sessionUiState.lastEventWallIso ?: localizedStringResource(language, R.string.training_unknown)}",
            style = MaterialTheme.typography.bodySmall,
          )
        }
        item {
          Text(
            text = "${localizedStringResource(language, R.string.training_buffered_audio)}: ${formatSeconds(sessionUiState.bufferedSeconds)}",
            style = MaterialTheme.typography.bodySmall,
          )
        }
        item {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
              onClick = { sessionManager.resetSessionCounts() },
              modifier = Modifier.weight(1f),
              enabled = sessionUiState.isRunning,
            ) {
              Text(text = localizedStringResource(language, R.string.training_reset_session_counts))
            }
            Button(
              onClick = { ensureSessionStarted() },
              modifier = Modifier.weight(1f),
            ) {
              Text(text = localizedStringResource(language, R.string.training_retry_mic))
            }
          }
        }
        item {
          AudioSignalPanel(
            language = language,
            signalHistory = sessionUiState.audioSignalHistory,
            rmsLevel = sessionUiState.audioRmsLevel,
            peakLevel = sessionUiState.audioPeakLevel,
          )
        }
      }
      item {
        Spacer(modifier = Modifier.height(8.dp))
      }
    }
  }
}

@Composable
private fun AudioSignalPanel(
  language: String,
  signalHistory: List<Float>,
  rmsLevel: Float,
  peakLevel: Float,
) {
  val panelColor = MaterialTheme.colorScheme.primary
  val chartBackground = MaterialTheme.colorScheme.surface
  val barColor = panelColor.copy(alpha = 0.8f)
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(
        color = panelColor.copy(alpha = 0.08f),
        shape = RoundedCornerShape(10.dp),
      )
      .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      text = localizedStringResource(language, R.string.training_audio_signal),
      style = MaterialTheme.typography.labelLarge,
      color = panelColor,
    )
    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(70.dp)
        .background(
          color = chartBackground,
          shape = RoundedCornerShape(8.dp),
        )
    ) {
      val values = if (signalHistory.isEmpty()) listOf(0f) else signalHistory.takeLast(56)
      val count = values.size.coerceAtLeast(1)
      val step = size.width / count.toFloat()
      values.forEachIndexed { index, value ->
        val level = value.coerceIn(0f, 1f)
        val barHeight = level * size.height
        drawRect(
          color = barColor,
          topLeft = Offset(x = index * step, y = size.height - barHeight),
          size = Size(width = step * 0.7f, height = barHeight)
        )
      }
    }
    Text(
      text = "${localizedStringResource(language, R.string.training_audio_rms)}: ${(rmsLevel * 100).toInt()}%  " +
          "${localizedStringResource(language, R.string.training_audio_peak)}: ${(peakLevel * 100).toInt()}%",
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun MicStatusBanner(language: String, microphoneStatus: MicrophoneStatus) {
  val (text, color) = when (microphoneStatus) {
    is MicrophoneStatus.Listening -> localizedStringResource(language, R.string.training_mic_listening) to Color(0xFF1B5E20)
    is MicrophoneStatus.Ready -> localizedStringResource(language, R.string.training_mic_ready) to Color(0xFF0277BD)
    is MicrophoneStatus.Unavailable -> localizedStringResource(language, R.string.training_mic_unavailable) to Color(0xFFB71C1C)
    is MicrophoneStatus.Error -> "${localizedStringResource(language, R.string.training_mic_error)}: ${microphoneStatus.message}" to Color(0xFFB71C1C)
  }
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small)
      .padding(horizontal = 12.dp, vertical = 8.dp)
  ) {
    Text(text = text, color = color)
  }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
  Box(
    modifier = Modifier
      .background(color.copy(alpha = 0.14f), shape = MaterialTheme.shapes.small)
      .padding(horizontal = 10.dp, vertical = 4.dp)
  ) {
    Text(text = text, color = color, style = MaterialTheme.typography.labelLarge)
  }
}

@Composable
private fun ProfileNameDialog(
  title: String,
  confirmText: String,
  dismissText: String,
  initialValue: String,
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit,
) {
  var text by remember(initialValue) { mutableStateOf(initialValue) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(text = title) },
    text = {
      OutlinedTextField(
        value = text,
        onValueChange = { updated -> text = updated },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
      )
    },
    confirmButton = {
      Button(onClick = { onConfirm(text) }, enabled = text.trim().isNotEmpty()) {
        Text(text = confirmText)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(text = dismissText)
      }
    }
  )
}

@Composable
private fun ConfirmDialog(
  title: String,
  message: String,
  confirmText: String,
  dismissText: String,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(text = title) },
    text = { Text(text = message) },
    confirmButton = {
      Button(onClick = onConfirm) {
        Text(text = confirmText)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(text = dismissText)
      }
    }
  )
}

private fun resolvePrimaryAction(profile: TrainingProfile): PrimaryProfileAction {
  return when (profile.learningStatus) {
    ProfileLearningStatus.NotReady -> PrimaryProfileAction.StartOrContinueTraining
    ProfileLearningStatus.Ready -> PrimaryProfileAction.ComputeLearnedTrigger
    ProfileLearningStatus.Outdated -> PrimaryProfileAction.RecomputeLearnedTrigger
    ProfileLearningStatus.Learned -> PrimaryProfileAction.LearnedUpToDate
  }
}

@Composable
private fun primaryActionText(language: String, action: PrimaryProfileAction, profile: TrainingProfile): String {
  return when (action) {
    PrimaryProfileAction.StartOrContinueTraining -> {
      if (profile.hasData) {
        localizedStringResource(language, R.string.training_continue_training)
      } else {
        localizedStringResource(language, R.string.start_training)
      }
    }
    PrimaryProfileAction.ComputeLearnedTrigger -> localizedStringResource(language, R.string.training_compute_learned_trigger)
    PrimaryProfileAction.RecomputeLearnedTrigger -> localizedStringResource(language, R.string.training_recompute_learned_trigger)
    PrimaryProfileAction.LearnedUpToDate -> localizedStringResource(language, R.string.training_learned_up_to_date)
  }
}

@Composable
private fun profileStatusBadge(language: String, status: ProfileLearningStatus): Pair<String, Color> {
  return when (status) {
    ProfileLearningStatus.NotReady -> localizedStringResource(language, R.string.training_status_not_ready) to Color(0xFFB71C1C)
    ProfileLearningStatus.Ready -> localizedStringResource(language, R.string.training_status_ready) to Color(0xFF1B5E20)
    ProfileLearningStatus.Learned -> localizedStringResource(language, R.string.training_status_learned) to Color(0xFF0D47A1)
    ProfileLearningStatus.Outdated -> localizedStringResource(language, R.string.training_status_outdated) to Color(0xFFE65100)
  }
}

private fun formatSeconds(seconds: Double): String = String.format("%.1fs", seconds.coerceAtLeast(0.0))

@Preview(showBackground = true)
@Composable
fun TrainingScreenPreview() {
  EspressoTimerMaterialTheme {
    TrainingScreen(onNavigateBack = {}, language = DEFAULT_LANGUAGE)
  }
}
