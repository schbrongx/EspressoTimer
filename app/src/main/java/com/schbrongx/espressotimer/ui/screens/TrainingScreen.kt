package com.schbrongx.espressotimer.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.schbrongx.espressotimer.DEFAULT_LANGUAGE
import com.schbrongx.espressotimer.EspressoTimerMaterialTheme
import com.schbrongx.espressotimer.R
import com.schbrongx.espressotimer.training.AndroidAudioBackend
import com.schbrongx.espressotimer.training.ComputeLearnedResult
import com.schbrongx.espressotimer.training.LearningPipeline
import com.schbrongx.espressotimer.training.MicrophoneStatus
import com.schbrongx.espressotimer.training.ProfileStatusBadge
import com.schbrongx.espressotimer.training.ProfilesRepository
import com.schbrongx.espressotimer.training.TrainingConfig
import com.schbrongx.espressotimer.training.TrainingProfile
import com.schbrongx.espressotimer.training.TrainingSessionManager
import com.schbrongx.espressotimer.training.TrainingSessionUiState
import com.schbrongx.espressotimer.training.TrainingStorage
import com.schbrongx.espressotimer.utils.localizedStringResource

private enum class TrainingRoute {
  Profiles,
  Session
}

private enum class PrimaryProfileAction {
  StartTraining,
  ContinueTraining,
  Compute,
  Recompute,
  LearnedUpToDate
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingScreen(onNavigateBack: () -> Unit, language: String) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }

  val storage = remember { TrainingStorage(context.applicationContext) }
  val profilesRepository = remember { ProfilesRepository(storage) }
  val sessionManager = remember {
    TrainingSessionManager(
      storage = storage,
      profilesRepository = profilesRepository,
      audioBackend = AndroidAudioBackend(context.applicationContext),
    )
  }
  val learningPipeline = remember { LearningPipeline(storage, profilesRepository) }

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
  var profileBusyId by remember { mutableStateOf<String?>(null) }
  val learnedSuccessText = localizedStringResource(language, R.string.training_learned_success)
  val learnedUpToDateText = localizedStringResource(language, R.string.training_learned_up_to_date)

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
    snackbarHost = { SnackbarHost(snackbarHostState) }
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
                isBusy = profileBusyId == profile.id,
                primaryAction = primaryAction,
                onSelect = {
                  profilesRepository.selectActiveProfile(profile.id)
                  refreshProfiles()
                },
                onRename = {
                  renameProfile = profile
                },
                onDelete = {
                  deleteProfile = profile
                },
                onReset = {
                  resetProfile = profile
                },
                onPrimaryAction = {
                  when (primaryAction) {
                    PrimaryProfileAction.StartTraining,
                    PrimaryProfileAction.ContinueTraining -> {
                      profilesRepository.selectActiveProfile(profile.id)
                      refreshProfiles()
                      selectedSessionProfileId = profile.id
                      route = TrainingRoute.Session
                    }

                    PrimaryProfileAction.Compute,
                    PrimaryProfileAction.Recompute -> {
                      scope.launch {
                        profileBusyId = profile.id
                        val computeResult = withContext(Dispatchers.Default) {
                          learningPipeline.computeLearnedTrigger(profile)
                        }
                        refreshProfiles()
                        val message = when (computeResult) {
                          is ComputeLearnedResult.Success -> {
                            learnedSuccessText
                          }

                          is ComputeLearnedResult.Error -> {
                            computeResult.message
                          }
                        }
                        snackbarHostState.showSnackbar(message)
                        profileBusyId = null
                      }
                    }

                    PrimaryProfileAction.LearnedUpToDate -> {
                      scope.launch {
                        snackbarHostState.showSnackbar(learnedUpToDateText)
                      }
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
  isBusy: Boolean,
  primaryAction: PrimaryProfileAction,
  onSelect: () -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit,
  onReset: () -> Unit,
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
        if (isActive) {
          StatusBadge(text = localizedStringResource(language, R.string.training_active), color = MaterialTheme.colorScheme.primary)
        }
      }

      Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text = "${localizedStringResource(language, R.string.training_positives)}: ${profile.positivesCount}")
        Text(text = "${localizedStringResource(language, R.string.training_negatives)}: ${profile.negativesCount}")
      }

      StatusBadge(
        text = statusLabel(language, profile.statusBadge),
        color = statusColor(profile.statusBadge),
      )

      if (profile.hasLearnedArtifact && !profile.learnedAtIso.isNullOrBlank()) {
        Text(
          text = "${localizedStringResource(language, R.string.training_last_computed)}: ${profile.learnedAtIso}",
          style = MaterialTheme.typography.bodySmall
        )
        Text(
          text = "${localizedStringResource(language, R.string.training_quality)}: ${profile.learnedQuality ?: localizedStringResource(language, R.string.training_unknown)}",
          style = MaterialTheme.typography.bodySmall
        )
      }

      Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!isActive) {
          TextButton(onClick = onSelect) {
            Text(text = localizedStringResource(language, R.string.training_set_active))
          }
        }
        IconButton(onClick = onRename) {
          Icon(Icons.Rounded.Edit, contentDescription = localizedStringResource(language, R.string.training_rename_profile))
        }
        IconButton(onClick = onDelete) {
          Icon(Icons.Rounded.Delete, contentDescription = localizedStringResource(language, R.string.training_delete_profile))
        }
        IconButton(onClick = onReset) {
          Icon(Icons.Rounded.Replay, contentDescription = localizedStringResource(language, R.string.training_reset_data))
        }
      }

      Button(
        onClick = onPrimaryAction,
        enabled = !isBusy && primaryAction != PrimaryProfileAction.LearnedUpToDate,
        modifier = Modifier.fillMaxWidth(),
      ) {
        if (isBusy) {
          CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = MaterialTheme.colorScheme.onPrimary,
            strokeWidth = 2.dp,
          )
        } else {
          Text(text = primaryActionText(language, primaryAction))
        }
      }

      if (primaryAction == PrimaryProfileAction.LearnedUpToDate) {
        Text(
          text = localizedStringResource(language, R.string.training_learned_up_to_date),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary
        )
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
  var sessionUiState by remember { mutableStateOf(TrainingSessionUiState()) }
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

  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Text(text = "${localizedStringResource(language, R.string.training_profile)}: ${profile.name}", style = MaterialTheme.typography.titleMedium)
    MicStatusBanner(language = language, microphoneStatus = sessionUiState.microphoneStatus)

    Text(text = "${localizedStringResource(language, R.string.training_positives)}: ${sessionUiState.totalPositives}")
    Text(text = "${localizedStringResource(language, R.string.training_negatives)}: ${sessionUiState.totalNegatives}")
    Text(
      text = "${localizedStringResource(language, R.string.training_ready_in)} ${sessionUiState.missingPositives} / ${sessionUiState.missingNegatives}",
      style = MaterialTheme.typography.bodySmall,
    )
    Text(
      text = "${localizedStringResource(language, R.string.training_session_counts)} ${sessionUiState.sessionPositives} / ${sessionUiState.sessionNegatives}",
      style = MaterialTheme.typography.bodySmall,
    )

    if (!sessionUiState.infoMessage.isNullOrBlank()) {
      Text(
        text = sessionUiState.infoMessage ?: "",
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodySmall,
      )
    }

    Spacer(modifier = Modifier.height(8.dp))

    Button(
      onClick = { sessionManager.tapShotStart() },
      enabled = sessionUiState.isRunning,
      modifier = Modifier
        .fillMaxWidth()
        .height(220.dp),
      colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
      Text(
        text = localizedStringResource(language, R.string.training_shot_start),
        style = MaterialTheme.typography.headlineSmall,
      )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      Button(
        onClick = { onStop() },
        modifier = Modifier.weight(1f)
      ) {
        Text(text = localizedStringResource(language, R.string.stop_training))
      }

      Button(
        onClick = { sessionManager.resetSessionCounts() },
        modifier = Modifier.weight(1f),
        enabled = sessionUiState.isRunning,
      ) {
        Text(text = localizedStringResource(language, R.string.training_reset_session_counts))
      }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      Button(
        onClick = { sessionManager.addBackgroundSampleNow() },
        modifier = Modifier.weight(1f),
        enabled = sessionUiState.isRunning,
      ) {
        Text(text = localizedStringResource(language, R.string.training_add_background_now))
      }

      Button(
        onClick = { ensureSessionStarted() },
        modifier = Modifier.weight(1f),
      ) {
        Text(text = localizedStringResource(language, R.string.training_retry_mic))
      }
    }
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
  if (!profile.isReady) {
    return if (profile.positivesCount > 0 || profile.negativesCount > 0) {
      PrimaryProfileAction.ContinueTraining
    } else {
      PrimaryProfileAction.StartTraining
    }
  }
  if (profile.hasLearnedArtifact && profile.isOutdated) {
    return PrimaryProfileAction.Recompute
  }
  if (!profile.hasLearnedArtifact) {
    return PrimaryProfileAction.Compute
  }
  return PrimaryProfileAction.LearnedUpToDate
}

@Composable
private fun primaryActionText(language: String, action: PrimaryProfileAction): String {
  return when (action) {
    PrimaryProfileAction.StartTraining -> localizedStringResource(language, R.string.start_training)
    PrimaryProfileAction.ContinueTraining -> localizedStringResource(language, R.string.training_continue_training)
    PrimaryProfileAction.Compute -> localizedStringResource(language, R.string.training_compute_learned_trigger)
    PrimaryProfileAction.Recompute -> localizedStringResource(language, R.string.training_recompute_learned_trigger)
    PrimaryProfileAction.LearnedUpToDate -> localizedStringResource(language, R.string.training_learned_up_to_date)
  }
}

@Composable
private fun statusLabel(language: String, badge: ProfileStatusBadge): String {
  return when (badge) {
    ProfileStatusBadge.NotReady -> localizedStringResource(language, R.string.training_status_not_ready)
    ProfileStatusBadge.Ready -> localizedStringResource(language, R.string.training_status_ready)
    ProfileStatusBadge.Learned -> localizedStringResource(language, R.string.training_status_learned)
    ProfileStatusBadge.Outdated -> localizedStringResource(language, R.string.training_status_outdated)
  }
}

private fun statusColor(status: ProfileStatusBadge): Color {
  return when (status) {
    ProfileStatusBadge.NotReady -> Color(0xFF6D4C41)
    ProfileStatusBadge.Ready -> Color(0xFF2E7D32)
    ProfileStatusBadge.Learned -> Color(0xFF1565C0)
    ProfileStatusBadge.Outdated -> Color(0xFFE65100)
  }
}

@Preview(showBackground = true)
@Composable
fun TrainingScreenPreview() {
  EspressoTimerMaterialTheme {
    TrainingScreen(onNavigateBack = {}, language = DEFAULT_LANGUAGE)
  }
}
