package com.schbrongx.espressotimer.training

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface MicrophoneStatus {
  data object Ready : MicrophoneStatus
  data object Listening : MicrophoneStatus
  data class Error(val message: String) : MicrophoneStatus
  data object Unavailable : MicrophoneStatus
}

typealias AudioFramesCallback = (samples: ShortArray, chunkStartMonotonicSec: Double, chunkEndMonotonicSec: Double) -> Unit

interface AudioBackend {
  val status: StateFlow<MicrophoneStatus>
  val deviceInfo: String?
  fun start(onFrames: AudioFramesCallback): Boolean
  fun stop()
}

class AndroidAudioBackend(private val context: Context) : AudioBackend {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val statusFlow = MutableStateFlow<MicrophoneStatus>(MicrophoneStatus.Ready)
  private var recorder: AudioRecord? = null
  private var readJob: Job? = null

  override val status: StateFlow<MicrophoneStatus> = statusFlow
  override val deviceInfo: String = "AndroidAudioRecord/${TrainingConfig.sampleRateHz}Hz/mono/pcm16"

  override fun start(onFrames: AudioFramesCallback): Boolean {
    stop()

    if (!hasRecordPermission()) {
      statusFlow.value = MicrophoneStatus.Error(message = "Microphone permission not granted.")
      return false
    }

    val minBuffer = AudioRecord.getMinBufferSize(
      TrainingConfig.sampleRateHz,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
    )
    if (minBuffer <= 0) {
      statusFlow.value = MicrophoneStatus.Unavailable
      return false
    }
    val bufferSize = maxOf(minBuffer, TrainingConfig.sampleRateHz / 2)

    val record = try {
      AudioRecord(
        MediaRecorder.AudioSource.MIC,
        TrainingConfig.sampleRateHz,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferSize,
      )
    } catch (_: Throwable) {
      null
    }

    if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
      record?.release()
      statusFlow.value = MicrophoneStatus.Unavailable
      return false
    }

    recorder = record
    return try {
      record.startRecording()
      statusFlow.value = MicrophoneStatus.Listening
      val frameSize = 1024
      readJob = scope.launch {
        val temp = ShortArray(frameSize)
        while (isActive) {
          val readCount = record.read(temp, 0, temp.size)
          when {
            readCount > 0 -> {
              val endTimeSec = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0
              val durationSec = readCount.toDouble() / TrainingConfig.sampleRateHz.toDouble()
              val startTimeSec = endTimeSec - durationSec
              onFrames(temp.copyOf(readCount), startTimeSec, endTimeSec)
            }

            readCount == AudioRecord.ERROR_INVALID_OPERATION -> {
              statusFlow.value = MicrophoneStatus.Error(message = "Microphone read failed (invalid operation).")
            }

            readCount == AudioRecord.ERROR_BAD_VALUE -> {
              statusFlow.value = MicrophoneStatus.Error(message = "Microphone read failed (bad value).")
            }
          }
        }
      }
      true
    } catch (_: Throwable) {
      stop()
      statusFlow.value = MicrophoneStatus.Error(message = "Could not start microphone recording.")
      false
    }
  }

  override fun stop() {
    readJob?.cancel()
    readJob = null
    recorder?.let { record ->
      runCatching {
        if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
          record.stop()
        }
      }
      runCatching { record.release() }
    }
    recorder = null
    statusFlow.value = MicrophoneStatus.Ready
  }

  fun close() {
    stop()
    scope.cancel()
  }

  private fun hasRecordPermission(): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
  }
}
