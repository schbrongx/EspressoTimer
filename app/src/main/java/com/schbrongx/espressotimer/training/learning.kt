package com.schbrongx.espressotimer.training

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class LearningFeatureConfig(
  val type: String = "log_mel_stats",
  val sampleRateHz: Int = TrainingConfig.sampleRateHz,
  val frameSize: Int = 400,
  val hopSize: Int = 160,
  val fftSize: Int = 512,
  val melBands: Int = 24,
  val minFrequencyHz: Double = 50.0,
  val maxFrequencyHz: Double = 7_600.0,
)

data class LearningMetrics(
  val accuracy: Double,
  val precision: Double,
  val recall: Double,
  val f1: Double,
)

data class LearnedTriggerSummary(
  val profileId: String,
  val learnedAtIso: String,
  val datasetRevision: Long,
  val datasetHash: String,
  val threshold: Double,
  val qualityLabel: LearningQualityLabel,
  val metrics: LearningMetrics,
  val positives: Int,
  val negatives: Int,
)

sealed interface LearnedTriggerComputationResult {
  data class Success(val summary: LearnedTriggerSummary) : LearnedTriggerComputationResult
  data class Blocked(val reason: String) : LearnedTriggerComputationResult
  data class Failure(val reason: String) : LearnedTriggerComputationResult
}

class LearnedTriggerLearner(
  private val storage: TrainingStorage,
  private val profilesRepository: ProfilesRepository,
) {
  private val featureConfig = LearningFeatureConfig()
  private val featureExtractor = LogMelStatsExtractor(featureConfig)

  fun computeLearnedTrigger(profileId: String): LearnedTriggerComputationResult {
    val profile = profilesRepository.getState().profiles.firstOrNull { it.id == profileId }
      ?: return LearnedTriggerComputationResult.Failure("Training profile not found.")

    val positives = storage.listSampleWavFiles(profileId, SampleLabel.Positive)
    val negatives = storage.listSampleWavFiles(profileId, SampleLabel.Negative)
    if (positives.size < TrainingConfig.minPositivesReady || negatives.size < TrainingConfig.minNegativesReady) {
      val missingPositives = (TrainingConfig.minPositivesReady - positives.size).coerceAtLeast(0)
      val missingNegatives = (TrainingConfig.minNegativesReady - negatives.size).coerceAtLeast(0)
      return LearnedTriggerComputationResult.Blocked(
        "Need at least ${TrainingConfig.minPositivesReady} positives and ${TrainingConfig.minNegativesReady} negatives. " +
            "Current: ${positives.size}/${negatives.size}. " +
            "Record $missingPositives more positives and $missingNegatives more negatives."
      )
    }

    val datasetHash = profile.datasetHash.ifBlank { storage.computeDatasetHash(profileId) }
    val splitSeed = seedFromDatasetHash(datasetHash)

    val examples = mutableListOf<FeatureExample>()
    positives.forEach { file ->
      val features = extractFeatures(file)
        ?: return LearnedTriggerComputationResult.Failure("Failed to read positive sample: ${file.name}. Re-record corrupted files.")
      examples += FeatureExample(sampleId = file.nameWithoutExtension, label = 1, features = features)
    }
    negatives.forEach { file ->
      val features = extractFeatures(file)
        ?: return LearnedTriggerComputationResult.Failure("Failed to read negative sample: ${file.name}. Re-record corrupted files.")
      examples += FeatureExample(sampleId = file.nameWithoutExtension, label = 0, features = features)
    }

    val split = splitDeterministically(examples, splitSeed)
    if (split.train.isEmpty() || split.validation.isEmpty()) {
      return LearnedTriggerComputationResult.Failure("Not enough data to build deterministic train/validation split.")
    }

    val normalization = computeNormalization(split.train.map { it.features })
    val normalizedTrain = split.train.map { example ->
      example.copy(features = normalize(example.features, normalization))
    }
    val normalizedValidation = split.validation.map { example ->
      example.copy(features = normalize(example.features, normalization))
    }

    val classifier = trainLogisticRegression(normalizedTrain)
    val validationProbabilities = normalizedValidation.map { example ->
      classifier.predictProbability(example.features)
    }
    val validationLabels = normalizedValidation.map { it.label }

    val thresholdSelection = selectThreshold(validationProbabilities, validationLabels)
    val metrics = thresholdSelection.metrics
    val qualityLabel = qualityFromF1(metrics.f1)
    val learnedAtIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

    val modelJson = buildModelJson(
      profile = profile,
      datasetHash = datasetHash,
      learnedAtIso = learnedAtIso,
      normalization = normalization,
      classifier = classifier,
      threshold = thresholdSelection.threshold,
    )
    val reportJson = buildReportJson(
      profile = profile,
      positives = positives.size,
      negatives = negatives.size,
      split = split,
      splitSeed = splitSeed,
      metrics = metrics,
      threshold = thresholdSelection.threshold,
      qualityLabel = qualityLabel,
      learnedAtIso = learnedAtIso,
    )

    return try {
      storage.writeLearnedArtifacts(
        profileId = profileId,
        modelJson = modelJson,
        reportJson = reportJson,
        learnedAtIso = learnedAtIso,
      )
      profilesRepository.markProfileLearned(
        profileId = profileId,
        learnedDatasetRevision = profile.datasetRevision,
        learnedDatasetHash = datasetHash,
        learnedAtIso = learnedAtIso,
        qualityLabel = qualityLabel,
      )
      LearnedTriggerComputationResult.Success(
        summary = LearnedTriggerSummary(
          profileId = profileId,
          learnedAtIso = learnedAtIso,
          datasetRevision = profile.datasetRevision,
          datasetHash = datasetHash,
          threshold = thresholdSelection.threshold,
          qualityLabel = qualityLabel,
          metrics = metrics,
          positives = positives.size,
          negatives = negatives.size,
        )
      )
    } catch (error: Throwable) {
      LearnedTriggerComputationResult.Failure("Could not write learned artifacts: ${error.message ?: "unknown error"}")
    }
  }

  private fun extractFeatures(file: File): DoubleArray? {
    val samples = runCatching { WavCodec.readPcm16MonoWav(file) }.getOrNull() ?: return null
    return runCatching { featureExtractor.extract(samples) }.getOrNull()
  }

  private fun buildModelJson(
    profile: TrainingProfile,
    datasetHash: String,
    learnedAtIso: String,
    normalization: NormalizationStats,
    classifier: LogisticRegressionModel,
    threshold: Double,
  ): JSONObject {
    return JSONObject().apply {
      put("format_version", 1)
      put("created_at", learnedAtIso)
      put("profile_id", profile.id)
      put("dataset_revision", profile.datasetRevision)
      put("dataset_hash", datasetHash)
      put("feature_config", JSONObject().apply {
        put("type", featureConfig.type)
        put("sample_rate_hz", featureConfig.sampleRateHz)
        put("frame_size", featureConfig.frameSize)
        put("hop_size", featureConfig.hopSize)
        put("fft_size", featureConfig.fftSize)
        put("mel_bands", featureConfig.melBands)
        put("min_frequency_hz", featureConfig.minFrequencyHz)
        put("max_frequency_hz", featureConfig.maxFrequencyHz)
      })
      put("normalization", JSONObject().apply {
        put("mean", normalization.mean.toJsonArray())
        put("std", normalization.std.toJsonArray())
      })
      put("classifier", JSONObject().apply {
        put("type", "logistic_regression")
        put("weights", classifier.weights.toJsonArray())
        put("bias", classifier.bias)
        put("learning_rate", classifier.learningRate)
        put("epochs", classifier.epochs)
        put("l2", classifier.l2)
      })
      put("threshold", JSONObject().apply {
        put("value", threshold)
        put("selection", "best_validation_f1_with_tie_break_recall_precision_lower_threshold")
      })
    }
  }

  private fun buildReportJson(
    profile: TrainingProfile,
    positives: Int,
    negatives: Int,
    split: TrainValidationSplit,
    splitSeed: Int,
    metrics: LearningMetrics,
    threshold: Double,
    qualityLabel: LearningQualityLabel,
    learnedAtIso: String,
  ): JSONObject {
    val trainPositives = split.train.count { it.label == 1 }
    val trainNegatives = split.train.count { it.label == 0 }
    val validationPositives = split.validation.count { it.label == 1 }
    val validationNegatives = split.validation.count { it.label == 0 }

    return JSONObject().apply {
      put("format_version", 1)
      put("created_at", learnedAtIso)
      put("profile_id", profile.id)
      put("quality_label", qualityLabel.value)
      put("dataset", JSONObject().apply {
        put("positives", positives)
        put("negatives", negatives)
        put("total", positives + negatives)
        put("dataset_revision", profile.datasetRevision)
        put("split_method", "deterministic_stratified_shuffle_80_20")
        put("split_seed", splitSeed)
        put("train_count", split.train.size)
        put("validation_count", split.validation.size)
        put("train_positives", trainPositives)
        put("train_negatives", trainNegatives)
        put("validation_positives", validationPositives)
        put("validation_negatives", validationNegatives)
      })
      put("metrics", JSONObject().apply {
        put("accuracy", metrics.accuracy)
        put("precision", metrics.precision)
        put("recall", metrics.recall)
        put("f1", metrics.f1)
      })
      put("threshold", threshold)
      put("threshold_rationale", "Selected by maximizing validation F1. Tie-breakers: recall, precision, then lower threshold.")
    }
  }
}

private data class FeatureExample(
  val sampleId: String,
  val label: Int,
  val features: DoubleArray,
)

private data class TrainValidationSplit(
  val train: List<FeatureExample>,
  val validation: List<FeatureExample>,
)

private data class NormalizationStats(
  val mean: DoubleArray,
  val std: DoubleArray,
)

private data class LogisticRegressionModel(
  val weights: DoubleArray,
  val bias: Double,
  val learningRate: Double,
  val epochs: Int,
  val l2: Double,
) {
  fun predictProbability(features: DoubleArray): Double {
    var z = bias
    for (index in weights.indices) {
      z += weights[index] * features[index]
    }
    val clamped = z.coerceIn(-35.0, 35.0)
    return 1.0 / (1.0 + exp(-clamped))
  }
}

private data class ThresholdSelection(
  val threshold: Double,
  val metrics: LearningMetrics,
)

private class LogMelStatsExtractor(
  private val config: LearningFeatureConfig,
) {
  private val hannWindow = DoubleArray(config.frameSize) { index ->
    if (config.frameSize <= 1) {
      1.0
    } else {
      0.5 - 0.5 * cos((2.0 * PI * index) / (config.frameSize - 1))
    }
  }
  private val melFilterbank = buildMelFilterbank(config)

  fun extract(rawSamples: ShortArray): DoubleArray {
    val signal = DoubleArray(rawSamples.size) { index -> rawSamples[index].toDouble() / Short.MAX_VALUE.toDouble() }
    val frames = frameSignal(signal, config.frameSize, config.hopSize)
    val frameCount = max(1, frames.size)
    val perBandMeans = DoubleArray(config.melBands)
    val perBandSquareMeans = DoubleArray(config.melBands)

    if (frames.isEmpty()) {
      val padded = DoubleArray(config.frameSize)
      val logMel = extractLogMelFrame(padded)
      for (band in logMel.indices) {
        perBandMeans[band] = logMel[band]
        perBandSquareMeans[band] = logMel[band] * logMel[band]
      }
    } else {
      frames.forEach { frame ->
        val logMel = extractLogMelFrame(frame)
        for (band in logMel.indices) {
          perBandMeans[band] += logMel[band]
          perBandSquareMeans[band] += logMel[band] * logMel[band]
        }
      }
    }

    for (band in 0 until config.melBands) {
      perBandMeans[band] /= frameCount.toDouble()
      perBandSquareMeans[band] /= frameCount.toDouble()
    }

    val featureVector = DoubleArray(config.melBands * 2)
    for (band in 0 until config.melBands) {
      val mean = perBandMeans[band]
      val variance = max(0.0, perBandSquareMeans[band] - mean * mean)
      featureVector[band] = mean
      featureVector[band + config.melBands] = sqrt(variance)
    }
    return featureVector
  }

  private fun extractLogMelFrame(frame: DoubleArray): DoubleArray {
    val real = DoubleArray(config.fftSize)
    val imag = DoubleArray(config.fftSize)
    val copySize = min(frame.size, config.frameSize)
    for (index in 0 until copySize) {
      real[index] = frame[index] * hannWindow[index]
    }

    fft(real, imag)

    val spectrumBins = config.fftSize / 2 + 1
    val power = DoubleArray(spectrumBins)
    for (bin in 0 until spectrumBins) {
      val re = real[bin]
      val im = imag[bin]
      power[bin] = re * re + im * im
    }

    val logMel = DoubleArray(config.melBands)
    for (band in 0 until config.melBands) {
      var energy = 0.0
      val filter = melFilterbank[band]
      for (bin in 0 until spectrumBins) {
        val weight = filter[bin]
        if (weight > 0.0) {
          energy += power[bin] * weight
        }
      }
      logMel[band] = ln(1.0 + energy)
    }
    return logMel
  }

  private fun frameSignal(signal: DoubleArray, frameSize: Int, hopSize: Int): List<DoubleArray> {
    if (signal.isEmpty()) {
      return emptyList()
    }
    if (signal.size <= frameSize) {
      val padded = DoubleArray(frameSize)
      for (index in signal.indices) {
        padded[index] = signal[index]
      }
      return listOf(padded)
    }

    val frames = mutableListOf<DoubleArray>()
    var start = 0
    while (start + frameSize <= signal.size) {
      frames += signal.copyOfRange(start, start + frameSize)
      start += hopSize
    }
    if (start < signal.size) {
      val tail = DoubleArray(frameSize)
      val remaining = signal.size - start
      for (index in 0 until remaining) {
        tail[index] = signal[start + index]
      }
      frames += tail
    }
    return frames
  }
}

private fun computeNormalization(features: List<DoubleArray>): NormalizationStats {
  val dimension = features.firstOrNull()?.size ?: 0
  val mean = DoubleArray(dimension)
  val std = DoubleArray(dimension)
  if (dimension == 0 || features.isEmpty()) {
    return NormalizationStats(mean = mean, std = std)
  }

  val count = features.size.toDouble()
  features.forEach { vector ->
    for (index in 0 until dimension) {
      mean[index] += vector[index]
    }
  }
  for (index in 0 until dimension) {
    mean[index] /= count
  }

  features.forEach { vector ->
    for (index in 0 until dimension) {
      val diff = vector[index] - mean[index]
      std[index] += diff * diff
    }
  }
  for (index in 0 until dimension) {
    std[index] = sqrt(std[index] / count).coerceAtLeast(1e-6)
  }

  return NormalizationStats(mean = mean, std = std)
}

private fun normalize(features: DoubleArray, normalization: NormalizationStats): DoubleArray {
  if (features.isEmpty()) {
    return features
  }
  return DoubleArray(features.size) { index ->
    (features[index] - normalization.mean[index]) / normalization.std[index]
  }
}

private fun trainLogisticRegression(trainExamples: List<FeatureExample>): LogisticRegressionModel {
  val dimension = trainExamples.first().features.size
  val weights = DoubleArray(dimension)
  var bias = 0.0

  val learningRate = 0.08
  val epochs = 500
  val l2 = 1e-4
  val size = trainExamples.size.toDouble()

  repeat(epochs) {
    val gradientW = DoubleArray(dimension)
    var gradientB = 0.0

    trainExamples.forEach { example ->
      var linear = bias
      for (index in 0 until dimension) {
        linear += weights[index] * example.features[index]
      }
      val probability = 1.0 / (1.0 + exp(-linear.coerceIn(-35.0, 35.0)))
      val error = probability - example.label.toDouble()
      for (index in 0 until dimension) {
        gradientW[index] += error * example.features[index]
      }
      gradientB += error
    }

    for (index in 0 until dimension) {
      val regularized = (gradientW[index] / size) + l2 * weights[index]
      weights[index] -= learningRate * regularized
    }
    bias -= learningRate * (gradientB / size)
  }

  return LogisticRegressionModel(
    weights = weights,
    bias = bias,
    learningRate = learningRate,
    epochs = epochs,
    l2 = l2,
  )
}

private fun splitDeterministically(examples: List<FeatureExample>, seed: Int): TrainValidationSplit {
  val positives = examples.filter { it.label == 1 }.sortedBy { it.sampleId }
  val negatives = examples.filter { it.label == 0 }.sortedBy { it.sampleId }

  val shuffledPositives = positives.shuffled(Random(seed xor 0x13579BDF.toInt()))
  val shuffledNegatives = negatives.shuffled(Random(seed xor 0x2468ACE0.toInt()))

  val posValidationCount = validationCountForClass(shuffledPositives.size)
  val negValidationCount = validationCountForClass(shuffledNegatives.size)

  val validation = mutableListOf<FeatureExample>()
  val train = mutableListOf<FeatureExample>()

  validation += shuffledPositives.take(posValidationCount)
  validation += shuffledNegatives.take(negValidationCount)
  train += shuffledPositives.drop(posValidationCount)
  train += shuffledNegatives.drop(negValidationCount)

  return TrainValidationSplit(
    train = train.sortedBy { it.sampleId },
    validation = validation.sortedBy { it.sampleId },
  )
}

private fun validationCountForClass(size: Int): Int {
  if (size <= 1) {
    return 0
  }
  val desired = floor(size * 0.2).toInt().coerceAtLeast(1)
  return desired.coerceAtMost(size - 1)
}

private fun selectThreshold(probabilities: List<Double>, labels: List<Int>): ThresholdSelection {
  val candidates = probabilities
    .map { probability -> probability.coerceIn(0.0, 1.0) }
    .toMutableSet()
    .apply {
      add(0.5)
      add(0.35)
      add(0.65)
    }
    .toList()
    .sorted()

  var bestThreshold = 0.5
  var bestMetrics = metricsForThreshold(probabilities, labels, threshold = 0.5)

  candidates.forEach { threshold ->
    val metrics = metricsForThreshold(probabilities, labels, threshold)
    val betterF1 = metrics.f1 > bestMetrics.f1 + 1e-12
    val sameF1 = abs(metrics.f1 - bestMetrics.f1) <= 1e-12
    val betterRecall = metrics.recall > bestMetrics.recall + 1e-12
    val sameRecall = abs(metrics.recall - bestMetrics.recall) <= 1e-12
    val betterPrecision = metrics.precision > bestMetrics.precision + 1e-12
    val samePrecision = abs(metrics.precision - bestMetrics.precision) <= 1e-12
    val lowerThreshold = threshold < bestThreshold

    if (
      betterF1 ||
      (sameF1 && betterRecall) ||
      (sameF1 && sameRecall && betterPrecision) ||
      (sameF1 && sameRecall && samePrecision && lowerThreshold)
    ) {
      bestThreshold = threshold
      bestMetrics = metrics
    }
  }

  return ThresholdSelection(threshold = bestThreshold, metrics = bestMetrics)
}

private fun metricsForThreshold(probabilities: List<Double>, labels: List<Int>, threshold: Double): LearningMetrics {
  var truePositive = 0
  var falsePositive = 0
  var trueNegative = 0
  var falseNegative = 0

  probabilities.zip(labels).forEach { (probability, label) ->
    val prediction = if (probability >= threshold) 1 else 0
    when {
      prediction == 1 && label == 1 -> truePositive += 1
      prediction == 1 && label == 0 -> falsePositive += 1
      prediction == 0 && label == 0 -> trueNegative += 1
      else -> falseNegative += 1
    }
  }

  val total = (truePositive + falsePositive + trueNegative + falseNegative).toDouble().coerceAtLeast(1.0)
  val precisionDenominator = (truePositive + falsePositive).toDouble()
  val recallDenominator = (truePositive + falseNegative).toDouble()
  val precision = if (precisionDenominator > 0.0) truePositive / precisionDenominator else 0.0
  val recall = if (recallDenominator > 0.0) truePositive / recallDenominator else 0.0
  val f1 = if (precision + recall > 0.0) 2.0 * precision * recall / (precision + recall) else 0.0

  return LearningMetrics(
    accuracy = (truePositive + trueNegative) / total,
    precision = precision,
    recall = recall,
    f1 = f1,
  )
}

private fun qualityFromF1(f1: Double): LearningQualityLabel {
  return when {
    f1 >= 0.85 -> LearningQualityLabel.Good
    f1 >= 0.70 -> LearningQualityLabel.OK
    else -> LearningQualityLabel.Weak
  }
}

private fun seedFromDatasetHash(datasetHash: String): Int {
  if (datasetHash.isBlank()) {
    return 17_173
  }
  var accumulator = 17_173
  datasetHash.chunked(8).forEach { chunk ->
    val value = runCatching { chunk.toLong(16) }.getOrDefault(0L)
    accumulator = (accumulator * 31) xor value.toInt()
  }
  return accumulator
}

private fun buildMelFilterbank(config: LearningFeatureConfig): Array<DoubleArray> {
  val spectrumBins = config.fftSize / 2 + 1
  val filterbank = Array(config.melBands) { DoubleArray(spectrumBins) }

  val minMel = hzToMel(config.minFrequencyHz)
  val maxMel = hzToMel(config.maxFrequencyHz)
  val melPoints = DoubleArray(config.melBands + 2) { index ->
    minMel + ((maxMel - minMel) * index / (config.melBands + 1).toDouble())
  }
  val hzPoints = melPoints.map { mel -> melToHz(mel) }
  val bins = hzPoints.map { hz ->
    val raw = floor(((config.fftSize + 1) * hz) / config.sampleRateHz).toInt()
    raw.coerceIn(0, spectrumBins - 1)
  }

  for (band in 1..config.melBands) {
    val left = bins[band - 1]
    val center = bins[band]
    val right = bins[band + 1]
    if (left == center || center == right) {
      continue
    }

    for (bin in left until center) {
      filterbank[band - 1][bin] = (bin - left).toDouble() / (center - left).toDouble()
    }
    for (bin in center until right) {
      filterbank[band - 1][bin] = (right - bin).toDouble() / (right - center).toDouble()
    }
  }

  return filterbank
}

private fun hzToMel(hz: Double): Double = 2595.0 * ln(1.0 + hz / 700.0)

private fun melToHz(mel: Double): Double = 700.0 * (exp(mel / 2595.0) - 1.0)

private fun fft(real: DoubleArray, imag: DoubleArray) {
  val size = real.size
  var j = 0
  for (index in 1 until size) {
    var bit = size shr 1
    while (j and bit != 0) {
      j = j xor bit
      bit = bit shr 1
    }
    j = j xor bit
    if (index < j) {
      val realTemp = real[index]
      real[index] = real[j]
      real[j] = realTemp
      val imagTemp = imag[index]
      imag[index] = imag[j]
      imag[j] = imagTemp
    }
  }

  var len = 2
  while (len <= size) {
    val angle = -2.0 * PI / len
    val wLenCos = cos(angle)
    val wLenSin = sin(angle)
    var start = 0
    while (start < size) {
      var wCos = 1.0
      var wSin = 0.0
      for (offset in 0 until len / 2) {
        val evenIndex = start + offset
        val oddIndex = start + offset + len / 2

        val oddReal = real[oddIndex] * wCos - imag[oddIndex] * wSin
        val oddImag = real[oddIndex] * wSin + imag[oddIndex] * wCos

        val evenReal = real[evenIndex]
        val evenImag = imag[evenIndex]

        real[evenIndex] = evenReal + oddReal
        imag[evenIndex] = evenImag + oddImag
        real[oddIndex] = evenReal - oddReal
        imag[oddIndex] = evenImag - oddImag

        val nextWCos = (wCos * wLenCos) - (wSin * wLenSin)
        val nextWSin = (wCos * wLenSin) + (wSin * wLenCos)
        wCos = nextWCos
        wSin = nextWSin
      }
      start += len
    }
    len = len shl 1
  }
}

private fun DoubleArray.toJsonArray(): JSONArray {
  return JSONArray().apply {
    this@toJsonArray.forEach { value -> put(value) }
  }
}
