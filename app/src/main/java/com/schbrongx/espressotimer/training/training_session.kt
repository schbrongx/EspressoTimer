package com.schbrongx.espressotimer.training

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class TrainingSessionUiState(
  val profileId: String? = null,
  val profileName: String = "",
  val isRunning: Boolean = false,
  val microphoneStatus: MicrophoneStatus = MicrophoneStatus.Ready,
  val sessionPositives: Int = 0,
  val sessionNegatives: Int = 0,
  val pendingPositiveEvents: Int = 0,
  val totalPositives: Int = 0,
  val totalNegatives: Int = 0,
  val totalRecordedSeconds: Double = 0.0,
  val lastEventWallIso: String? = null,
  val bufferedSeconds: Double = 0.0,
  val missingPositives: Int = TrainingConfig.minPositivesReady,
  val missingNegatives: Int = TrainingConfig.minNegativesReady,
  val infoMessage: String? = null,
)

private data class PendingTap(
  val tapMonotonicSec: Double,
  val tapWallIso: String,
)

private data class TimeWindow(val startSec: Double, val endSec: Double)

class TrainingSessionManager(
  private val storage: TrainingStorage,
  private val profilesRepository: ProfilesRepository,
  private val audioBackend: AudioBackend,
) {
  private val lock = Any()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val ringBuffer = TimestampedRingBuffer(
    sampleRateHz = TrainingConfig.sampleRateHz,
    retentionSeconds = TrainingConfig.ringBufferSeconds,
  )
  private val pendingTaps = mutableListOf<PendingTap>()
  private val positiveWindows = mutableListOf<TimeWindow>()
  private val random = Random(13_371)
  private var lastNegativeCaptureAtSec: Double = Double.NEGATIVE_INFINITY
  private var lastNegativeAttemptAtSec: Double = Double.NEGATIVE_INFINITY
  private var lastTapAtSec: Double = Double.NEGATIVE_INFINITY
  private var profile: TrainingProfile? = null

  private var sessionPositives: Int = 0
  private var sessionNegatives: Int = 0

  private val uiStateFlow = MutableStateFlow(TrainingSessionUiState())
  val uiState: StateFlow<TrainingSessionUiState> = uiStateFlow

  private var statusCollectorJob: Job? = null

  init {
    statusCollectorJob = scope.launch {
      audioBackend.status.collectLatest { status ->
        uiStateFlow.value = uiStateFlow.value.copy(microphoneStatus = status)
      }
    }
  }

  fun start(profile: TrainingProfile): Boolean {
    synchronized(lock) {
      this.profile = profile
      sessionPositives = 0
      sessionNegatives = 0
      pendingTaps.clear()
      positiveWindows.clear()
      ringBuffer.clear()
      lastNegativeCaptureAtSec = Double.NEGATIVE_INFINITY
      lastNegativeAttemptAtSec = Double.NEGATIVE_INFINITY
      lastTapAtSec = Double.NEGATIVE_INFINITY
      updateUiState(
        profile = profile,
        isRunning = false,
        infoMessage = null,
      )
    }

    val started = audioBackend.start { samples, chunkStartSec, chunkEndSec ->
      onAudioChunk(samples, chunkStartSec, chunkEndSec)
    }

    synchronized(lock) {
      updateUiState(
        profile = profile,
        isRunning = started,
        infoMessage = if (started) "Listening. Tap SHOT START exactly when the shot starts." else "Microphone unavailable. Check permission and audio device.",
      )
    }
    return started
  }

  fun stop() {
    synchronized(lock) {
      audioBackend.stop()
      pendingTaps.clear()
      updateUiState(
        profile = profile,
        isRunning = false,
        infoMessage = null,
      )
    }
  }

  fun resetSessionCounts() {
    synchronized(lock) {
      sessionPositives = 0
      sessionNegatives = 0
      updateUiState(
        profile = profile,
        isRunning = uiStateFlow.value.isRunning,
        infoMessage = "Session counters reset.",
      )
    }
  }

  fun addBackgroundSampleNow(): Boolean {
    synchronized(lock) {
      val activeProfile = profile ?: return false
      if (!uiStateFlow.value.isRunning) {
        uiStateFlow.value = uiStateFlow.value.copy(infoMessage = "Training session is not running.")
        return false
      }
      if (sessionNegatives >= TrainingConfig.maxNegativesPerSession) {
        uiStateFlow.value = uiStateFlow.value.copy(infoMessage = "Negative sample cap reached for this session.")
        return false
      }
      val nowSec = ringBuffer.latestMonotonicSec() ?: (SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0)
      val startSec = nowSec - TrainingConfig.negativeWindowSeconds
      val endSec = nowSec
      if (!ringBuffer.hasWindow(startSec, endSec)) {
        uiStateFlow.value = uiStateFlow.value.copy(infoMessage = "Not enough buffered audio yet.")
        return false
      }
      if (!isWindowFarFromPositives(startSec, endSec)) {
        uiStateFlow.value = uiStateFlow.value.copy(infoMessage = "Background sample is too close to a shot event.")
        return false
      }

      val samples = ringBuffer.extractWindow(startSec, endSec) ?: return false
      persistSample(
        profile = activeProfile,
        label = SampleLabel.Negative,
        samples = samples,
        tapMonotonicSec = nowSec,
        tapWallIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
      )
      sessionNegatives += 1
      lastNegativeCaptureAtSec = nowSec
      refreshProfileFromRepository(activeProfile.id)
      uiStateFlow.value = uiStateFlow.value.copy(infoMessage = "Background sample saved.")
      return true
    }
  }

  fun tapShotStart(): Boolean {
    synchronized(lock) {
      if (!uiStateFlow.value.isRunning) {
        uiStateFlow.value = uiStateFlow.value.copy(infoMessage = "Training session is not running.")
        return false
      }

      val tapMonotonicSec = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0
      if ((tapMonotonicSec - lastTapAtSec) < TrainingConfig.tapDebounceSeconds) {
        uiStateFlow.value = uiStateFlow.value.copy(infoMessage = "Tap ignored (debounce: 500 ms).")
        return false
      }
      lastTapAtSec = tapMonotonicSec

      val preRollStart = tapMonotonicSec - TrainingConfig.positivePreRollSeconds
      if (!ringBuffer.hasWindow(preRollStart, tapMonotonicSec)) {
        val buffered = ringBuffer.bufferedDurationSeconds()
        uiStateFlow.value = uiStateFlow.value.copy(
          infoMessage = "Buffering pre-roll audio (${String.format("%.1f", buffered)}s/${TrainingConfig.positivePreRollSeconds}s)."
        )
        return false
      }

      val tapWallIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
      pendingTaps += PendingTap(tapMonotonicSec = tapMonotonicSec, tapWallIso = tapWallIso)
      updateUiState(
        profile = profile,
        isRunning = true,
        infoMessage = "Shot start marker captured. Saving after post-roll completes...",
      )
      return true
    }
  }

  fun close() {
    stop()
    statusCollectorJob?.cancel()
    statusCollectorJob = null
    if (audioBackend is AndroidAudioBackend) {
      audioBackend.close()
    }
    scope.cancel()
  }

  fun setInfoMessage(message: String?) {
    synchronized(lock) {
      uiStateFlow.value = uiStateFlow.value.copy(infoMessage = message)
    }
  }

  private fun onAudioChunk(samples: ShortArray, chunkStartSec: Double, chunkEndSec: Double) {
    synchronized(lock) {
      if (!uiStateFlow.value.isRunning) {
        return
      }
      ringBuffer.append(samples = samples, chunkStartMonotonicSec = chunkStartSec)
      processPendingPositives(chunkEndSec)
      processAutomaticNegatives(chunkEndSec)
      updateUiState(
        profile = profile,
        isRunning = uiStateFlow.value.isRunning,
        infoMessage = uiStateFlow.value.infoMessage,
      )
    }
  }

  private fun processPendingPositives(currentMonotonicSec: Double) {
    val activeProfile = profile ?: return
    if (pendingTaps.isEmpty()) {
      return
    }

    val iterator = pendingTaps.iterator()
    while (iterator.hasNext()) {
      val tap = iterator.next()
      val windowStart = tap.tapMonotonicSec - TrainingConfig.positivePreRollSeconds
      val windowEnd = tap.tapMonotonicSec + TrainingConfig.positivePostRollSeconds
      if (currentMonotonicSec < windowEnd) {
        continue
      }

      val samples = ringBuffer.extractWindow(windowStart, windowEnd)
      if (samples == null) {
        iterator.remove()
        uiStateFlow.value = uiStateFlow.value.copy(
          infoMessage = "Skipped one tap: positive window not fully available."
        )
        continue
      }

      persistSample(
        profile = activeProfile,
        label = SampleLabel.Positive,
        samples = samples,
        tapMonotonicSec = tap.tapMonotonicSec,
        tapWallIso = tap.tapWallIso,
      )
      positiveWindows += TimeWindow(startSec = windowStart, endSec = windowEnd)
      sessionPositives += 1
      iterator.remove()
      refreshProfileFromRepository(activeProfile.id)
      uiStateFlow.value = uiStateFlow.value.copy(infoMessage = "Positive sample saved.")
    }
  }

  private fun processAutomaticNegatives(currentMonotonicSec: Double) {
    val activeProfile = profile ?: return
    if (sessionNegatives >= TrainingConfig.maxNegativesPerSession) {
      return
    }

    val targetNegatives = min(
      TrainingConfig.maxNegativesPerSession,
      max(4, sessionPositives * TrainingConfig.targetNegativeMultiplier)
    )
    if (targetNegatives <= 0 || sessionNegatives >= targetNegatives) {
      return
    }
    if (currentMonotonicSec - lastNegativeCaptureAtSec < 1.0) {
      return
    }
    if (currentMonotonicSec - lastNegativeAttemptAtSec < 1.0) {
      return
    }
    lastNegativeAttemptAtSec = currentMonotonicSec

    val coverage = ringBuffer.coverageWindowSeconds() ?: return
    val minEnd = coverage.first + TrainingConfig.negativeWindowSeconds
    val maxEnd = coverage.second
    if (maxEnd <= minEnd) {
      return
    }

    repeat(10) {
      val windowEnd = random.nextDouble(minEnd, maxEnd)
      val windowStart = windowEnd - TrainingConfig.negativeWindowSeconds
      if (!ringBuffer.hasWindow(windowStart, windowEnd)) {
        return@repeat
      }
      if (!isWindowFarFromPositives(windowStart, windowEnd)) {
        return@repeat
      }
      val samples = ringBuffer.extractWindow(windowStart, windowEnd) ?: return@repeat
      persistSample(
        profile = activeProfile,
        label = SampleLabel.Negative,
        samples = samples,
        tapMonotonicSec = windowEnd,
        tapWallIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
      )
      sessionNegatives += 1
      lastNegativeCaptureAtSec = currentMonotonicSec
      refreshProfileFromRepository(activeProfile.id)
      return
    }
  }

  private fun isWindowFarFromPositives(windowStart: Double, windowEnd: Double): Boolean {
    val bufferedWindows = buildList {
      addAll(positiveWindows)
      pendingTaps.forEach { tap ->
        add(
          TimeWindow(
            startSec = tap.tapMonotonicSec - TrainingConfig.positivePreRollSeconds,
            endSec = tap.tapMonotonicSec + TrainingConfig.positivePostRollSeconds,
          )
        )
      }
    }

    val distance = TrainingConfig.negativeDistanceFromPositiveSeconds
    return bufferedWindows.all { positiveWindow ->
      windowEnd <= (positiveWindow.startSec - distance) || windowStart >= (positiveWindow.endSec + distance)
    }
  }

  private fun persistSample(
    profile: TrainingProfile,
    label: SampleLabel,
    samples: ShortArray,
    tapMonotonicSec: Double,
    tapWallIso: String,
  ) {
    val savedSample = storage.saveSample(
      request = SampleSaveRequest(
        profileId = profile.id,
        profileName = profile.name,
        label = label,
        samples = samples,
        tapTimeWallIso = tapWallIso,
        tapTimeMonotonicSec = tapMonotonicSec,
        sourceDeviceInfo = audioBackend.deviceInfo,
        windowPreSeconds = if (label == SampleLabel.Positive) TrainingConfig.positivePreRollSeconds else null,
        windowPostSeconds = if (label == SampleLabel.Positive) TrainingConfig.positivePostRollSeconds else null,
        windowLengthSeconds = if (label == SampleLabel.Negative) TrainingConfig.negativeWindowSeconds else null,
      )
    )
    val durationSeconds = samples.size.toDouble() / TrainingConfig.sampleRateHz.toDouble()
    profilesRepository.addSample(
      profileId = profile.id,
      label = label,
      durationSeconds = durationSeconds,
      eventTimeWallIso = tapWallIso,
      sampleSha256 = savedSample.sha256,
      sampleId = savedSample.sampleId,
    )
  }

  private fun refreshProfileFromRepository(profileId: String) {
    val refreshed = profilesRepository.getState().profiles.firstOrNull { it.id == profileId } ?: return
    profile = refreshed
    updateUiState(
      profile = refreshed,
      isRunning = uiStateFlow.value.isRunning,
      infoMessage = uiStateFlow.value.infoMessage,
    )
  }

  private fun updateUiState(profile: TrainingProfile?, isRunning: Boolean, infoMessage: String? = null) {
    val positives = profile?.positivesCount ?: 0
    val negatives = profile?.negativesCount ?: 0
    uiStateFlow.value = TrainingSessionUiState(
      profileId = profile?.id,
      profileName = profile?.name ?: "",
      isRunning = isRunning,
      microphoneStatus = uiStateFlow.value.microphoneStatus,
      sessionPositives = sessionPositives,
      sessionNegatives = sessionNegatives,
      pendingPositiveEvents = pendingTaps.size,
      totalPositives = positives,
      totalNegatives = negatives,
      totalRecordedSeconds = profile?.totalRecordedSeconds ?: 0.0,
      lastEventWallIso = profile?.lastEventWallIso,
      bufferedSeconds = ringBuffer.bufferedDurationSeconds(),
      missingPositives = (TrainingConfig.minPositivesReady - positives).coerceAtLeast(0),
      missingNegatives = (TrainingConfig.minNegativesReady - negatives).coerceAtLeast(0),
      infoMessage = infoMessage,
    )
  }
}
