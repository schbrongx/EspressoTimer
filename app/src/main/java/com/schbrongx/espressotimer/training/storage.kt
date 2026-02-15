package com.schbrongx.espressotimer.training

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class SampleLabel(val value: String, val folderName: String) {
  Positive(value = "positive", folderName = "positives"),
  Negative(value = "negative", folderName = "negatives")
}

data class SampleSaveRequest(
  val profileId: String,
  val profileName: String,
  val label: SampleLabel,
  val samples: ShortArray,
  val tapTimeWallIso: String,
  val tapTimeMonotonicSec: Double,
  val windowPreSeconds: Double? = null,
  val windowPostSeconds: Double? = null,
  val windowLengthSeconds: Double? = null,
)

data class SavedSample(
  val wavFile: File,
  val metadataFile: File,
  val sha256: String,
)

class TrainingStorage(private val context: Context) {
  companion object {
    private const val Tag = "TrainingStorage"
    private const val ProfilesFileName = "profiles.json"
    private const val EventsFileName = "events.jsonl"
    private const val ModelFileName = "model.json"
    private const val ReportFileName = "report.json"
    private const val LearnedAtFileName = "learned_at.txt"
  }

  private val dataRoot: File = File(context.filesDir, "data")
  private val trainingRoot: File = File(dataRoot, "training")

  init {
    ensureStorageLayout()
  }

  fun ensureStorageLayout() {
    if (!dataRoot.exists()) {
      dataRoot.mkdirs()
    }
    if (!trainingRoot.exists()) {
      trainingRoot.mkdirs()
    }
  }

  fun getDataRoot(): File = dataRoot

  fun getProfilesFile(): File = File(dataRoot, ProfilesFileName)

  fun getProfileTrainingDir(profileId: String): File = File(trainingRoot, profileId)

  fun getPositivesDir(profileId: String): File = File(getProfileTrainingDir(profileId), SampleLabel.Positive.folderName)

  fun getNegativesDir(profileId: String): File = File(getProfileTrainingDir(profileId), SampleLabel.Negative.folderName)

  fun getEventsFile(profileId: String): File = File(getProfileTrainingDir(profileId), EventsFileName)

  fun getLearnedDir(profileId: String): File = File(getProfileTrainingDir(profileId), "learned")

  fun getLearnedModelFile(profileId: String): File = File(getLearnedDir(profileId), ModelFileName)

  fun getLearnedReportFile(profileId: String): File = File(getLearnedDir(profileId), ReportFileName)

  fun getLearnedAtFile(profileId: String): File = File(getLearnedDir(profileId), LearnedAtFileName)

  fun ensureProfileLayout(profileId: String) {
    getPositivesDir(profileId).mkdirs()
    getNegativesDir(profileId).mkdirs()
    getLearnedDir(profileId).mkdirs()
    val eventsFile = getEventsFile(profileId)
    if (!eventsFile.exists()) {
      eventsFile.parentFile?.mkdirs()
      eventsFile.createNewFile()
    }
  }

  @Synchronized
  fun atomicWriteText(file: File, content: String) {
    file.parentFile?.mkdirs()
    val tempFile = File(file.parentFile, "${file.name}.tmp-${UUID.randomUUID()}")
    tempFile.writeText(content)
    if (!tempFile.renameTo(file)) {
      file.writeText(content)
      tempFile.delete()
    }
  }

  @Synchronized
  fun saveSample(request: SampleSaveRequest): SavedSample {
    ensureProfileLayout(request.profileId)
    val labelDir = if (request.label == SampleLabel.Positive) {
      getPositivesDir(request.profileId)
    } else {
      getNegativesDir(request.profileId)
    }

    val timestamp = System.currentTimeMillis()
    val sampleId = "${request.label.value}_${timestamp}_${UUID.randomUUID().toString().take(8)}"
    val wavFile = File(labelDir, "$sampleId.wav")
    val metadataFile = File(labelDir, "$sampleId.json")

    writePcm16MonoWav(
      file = wavFile,
      samples = request.samples,
      sampleRateHz = TrainingConfig.sampleRateHz
    )
    val sha256 = sha256Hex(wavFile)

    val metadata = JSONObject().apply {
      put("profile_id", request.profileId)
      put("profile_name", request.profileName)
      put("label", request.label.value)
      put("sample_rate", TrainingConfig.sampleRateHz)
      put("channels", TrainingConfig.channels)
      put("sample_width", TrainingConfig.sampleWidthBytes)
      put("tap_time_wall", request.tapTimeWallIso)
      put("tap_time_monotonic", request.tapTimeMonotonicSec)
      put("sha256", sha256)
      if (request.label == SampleLabel.Positive) {
        put("window_pre_s", request.windowPreSeconds ?: TrainingConfig.positivePreRollSeconds)
        put("window_post_s", request.windowPostSeconds ?: TrainingConfig.positivePostRollSeconds)
      } else {
        put("window_len_s", request.windowLengthSeconds ?: TrainingConfig.negativeWindowSeconds)
      }
    }
    atomicWriteText(metadataFile, metadata.toString(2))

    val event = JSONObject().apply {
      put("event_time_wall", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
      put("profile_id", request.profileId)
      put("profile_name", request.profileName)
      put("label", request.label.value)
      put("wav_file", wavFile.name)
      put("metadata_file", metadataFile.name)
      put("sha256", sha256)
      put("tap_time_wall", request.tapTimeWallIso)
      put("tap_time_monotonic", request.tapTimeMonotonicSec)
    }
    appendEvent(request.profileId, event)

    return SavedSample(wavFile = wavFile, metadataFile = metadataFile, sha256 = sha256)
  }

  @Synchronized
  fun appendEvent(profileId: String, event: JSONObject) {
    ensureProfileLayout(profileId)
    val eventsFile = getEventsFile(profileId)
    FileWriter(eventsFile, true).use { writer ->
      writer.append(event.toString())
      writer.appendLine()
    }
  }

  fun countSamples(profileId: String, label: SampleLabel): Int {
    val dir = if (label == SampleLabel.Positive) getPositivesDir(profileId) else getNegativesDir(profileId)
    if (!dir.exists()) {
      return 0
    }
    return dir.listFiles { file -> file.extension.lowercase() == "wav" }?.size ?: 0
  }

  fun listSampleWavFiles(profileId: String, label: SampleLabel): List<File> {
    val dir = if (label == SampleLabel.Positive) getPositivesDir(profileId) else getNegativesDir(profileId)
    if (!dir.exists()) {
      return emptyList()
    }
    return dir.listFiles { file -> file.extension.lowercase() == "wav" }
      ?.sortedBy { it.name }
      ?: emptyList()
  }

  @Synchronized
  fun resetProfileTrainingData(profileId: String) {
    val profileDir = getProfileTrainingDir(profileId)
    if (profileDir.exists() && !profileDir.deleteRecursively()) {
      Log.w(Tag, "Failed to fully delete profile directory: ${profileDir.absolutePath}")
    }
    ensureProfileLayout(profileId)
  }

  @Synchronized
  fun deleteProfileTrainingData(profileId: String) {
    val profileDir = getProfileTrainingDir(profileId)
    if (profileDir.exists() && !profileDir.deleteRecursively()) {
      Log.w(Tag, "Failed to delete profile directory: ${profileDir.absolutePath}")
    }
  }

  fun readLearnedReport(profileId: String): JSONObject? {
    val reportFile = getLearnedReportFile(profileId)
    if (!reportFile.exists()) {
      return null
    }
    return runCatching { JSONObject(reportFile.readText()) }.getOrNull()
  }

  @Synchronized
  fun writeLearnedArtifacts(
    profileId: String,
    modelJson: JSONObject,
    reportJson: JSONObject,
    learnedAtIso: String,
  ) {
    ensureProfileLayout(profileId)
    val learnedDir = getLearnedDir(profileId)
    learnedDir.mkdirs()

    atomicWriteText(getLearnedModelFile(profileId), modelJson.toString(2))
    atomicWriteText(getLearnedReportFile(profileId), reportJson.toString(2))
    atomicWriteText(getLearnedAtFile(profileId), learnedAtIso)
  }

  fun hasLearnedArtifacts(profileId: String): Boolean {
    return getLearnedModelFile(profileId).exists() && getLearnedReportFile(profileId).exists()
  }

  fun readLearnedAt(profileId: String): String? {
    val file = getLearnedAtFile(profileId)
    return if (file.exists()) file.readText().trim() else null
  }
}

object WavCodec {
  fun writePcm16MonoWav(file: File, samples: ShortArray, sampleRateHz: Int) {
    file.parentFile?.mkdirs()

    val byteRate = sampleRateHz * TrainingConfig.channels * TrainingConfig.sampleWidthBytes
    val dataSize = samples.size * TrainingConfig.sampleWidthBytes
    val chunkSize = 36 + dataSize

    FileOutputStream(file).use { output ->
      output.write("RIFF".toByteArray())
      output.write(intToLeBytes(chunkSize))
      output.write("WAVE".toByteArray())

      output.write("fmt ".toByteArray())
      output.write(intToLeBytes(16))
      output.write(shortToLeBytes(1))
      output.write(shortToLeBytes(TrainingConfig.channels.toShort()))
      output.write(intToLeBytes(sampleRateHz))
      output.write(intToLeBytes(byteRate))
      output.write(shortToLeBytes((TrainingConfig.channels * TrainingConfig.sampleWidthBytes).toShort()))
      output.write(shortToLeBytes((TrainingConfig.sampleWidthBytes * 8).toShort()))

      output.write("data".toByteArray())
      output.write(intToLeBytes(dataSize))

      val audioBytes = ByteArray(dataSize)
      val buffer = ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN)
      samples.forEach { sample -> buffer.putShort(sample) }
      output.write(audioBytes)
    }
  }

  fun readPcm16MonoWav(file: File): ShortArray {
    val bytes = FileInputStream(file).use { input -> input.readBytes() }
    require(bytes.size >= 44) { "Invalid WAV: too short" }
    require(String(bytes, 0, 4) == "RIFF") { "Invalid WAV: missing RIFF" }
    require(String(bytes, 8, 4) == "WAVE") { "Invalid WAV: missing WAVE" }

    var offset = 12
    var audioFormat = -1
    var channels = -1
    var bitsPerSample = -1
    var dataOffset = -1
    var dataSize = -1

    chunkLoop@ while (offset + 8 <= bytes.size) {
      val chunkId = String(bytes, offset, 4)
      val chunkSize = leInt(bytes, offset + 4)
      val chunkDataOffset = offset + 8
      if (chunkDataOffset + chunkSize > bytes.size) {
        break
      }
      when (chunkId) {
        "fmt " -> {
          audioFormat = leShort(bytes, chunkDataOffset).toInt()
          channels = leShort(bytes, chunkDataOffset + 2).toInt()
          bitsPerSample = leShort(bytes, chunkDataOffset + 14).toInt()
        }
        "data" -> {
          dataOffset = chunkDataOffset
          dataSize = chunkSize
          break@chunkLoop
        }
      }

      val paddedSize = if (chunkSize % 2 == 0) chunkSize else chunkSize + 1
      offset = chunkDataOffset + paddedSize
    }

    require(audioFormat == 1) { "Only PCM WAV is supported" }
    require(channels == 1) { "Only mono WAV is supported" }
    require(bitsPerSample == 16) { "Only 16-bit WAV is supported" }
    require(dataOffset >= 0 && dataSize >= 0) { "Invalid WAV: missing data chunk" }

    val sampleCount = dataSize / 2
    val result = ShortArray(sampleCount)
    var sampleIndex = 0
    var i = dataOffset
    while (sampleIndex < sampleCount && i + 1 < dataOffset + dataSize) {
      result[sampleIndex] = leShort(bytes, i)
      sampleIndex += 1
      i += 2
    }
    return result
  }

  private fun leInt(bytes: ByteArray, offset: Int): Int {
    return (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
  }

  private fun leShort(bytes: ByteArray, offset: Int): Short {
    return (((bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8))).toShort()
  }

  private fun intToLeBytes(value: Int): ByteArray {
    return byteArrayOf(
      (value and 0xFF).toByte(),
      ((value shr 8) and 0xFF).toByte(),
      ((value shr 16) and 0xFF).toByte(),
      ((value shr 24) and 0xFF).toByte(),
    )
  }

  private fun shortToLeBytes(value: Short): ByteArray {
    val intValue = value.toInt() and 0xFFFF
    return byteArrayOf(
      (intValue and 0xFF).toByte(),
      ((intValue shr 8) and 0xFF).toByte(),
    )
  }
}

fun sha256Hex(file: File): String {
  val digest = MessageDigest.getInstance("SHA-256")
  FileInputStream(file).use { input ->
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
      val read = input.read(buffer)
      if (read <= 0) {
        break
      }
      digest.update(buffer, 0, read)
    }
  }
  return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun writePcm16MonoWav(file: File, samples: ShortArray, sampleRateHz: Int) {
  WavCodec.writePcm16MonoWav(file = file, samples = samples, sampleRateHz = sampleRateHz)
}
