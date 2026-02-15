package com.schbrongx.espressotimer.training

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

data class LearningMetrics(
  val accuracy: Double,
  val precision: Double,
  val recall: Double,
  val f1: Double,
)

data class LearningResult(
  val learnedAtIso: String,
  val qualityLabel: String,
  val recommendedThreshold: Double,
  val validationMetrics: LearningMetrics,
)

sealed class ComputeLearnedResult {
  data class Success(val learningResult: LearningResult) : ComputeLearnedResult()
  data class Error(val message: String) : ComputeLearnedResult()
}

private data class FeatureExample(
  val file: File,
  val label: Int,
  val feature: DoubleArray,
)

private data class SplitSet(
  val train: List<FeatureExample>,
  val validation: List<FeatureExample>,
)

private data class NormalizationStats(
  val mean: DoubleArray,
  val std: DoubleArray,
)

private data class LogisticModel(
  val weights: DoubleArray,
  val bias: Double,
)

private data class ThresholdSelection(
  val threshold: Double,
  val metrics: LearningMetrics,
)

class LearningPipeline(
  private val storage: TrainingStorage,
  private val profilesRepository: ProfilesRepository,
) {
  fun computeLearnedTrigger(profile: TrainingProfile): ComputeLearnedResult {
    if (!profile.isReady) {
      val missingPos = (TrainingConfig.minPositivesReady - profile.positivesCount).coerceAtLeast(0)
      val missingNeg = (TrainingConfig.minNegativesReady - profile.negativesCount).coerceAtLeast(0)
      return ComputeLearnedResult.Error(
        message = "Insufficient data. Missing $missingPos positives and $missingNeg negatives."
      )
    }

    return try {
      val positives = storage.listSampleWavFiles(profile.id, SampleLabel.Positive)
      val negatives = storage.listSampleWavFiles(profile.id, SampleLabel.Negative)
      if (positives.size < TrainingConfig.minPositivesReady || negatives.size < TrainingConfig.minNegativesReady) {
        return ComputeLearnedResult.Error(
          message = "Insufficient samples on disk. Need at least ${TrainingConfig.minPositivesReady} positives and ${TrainingConfig.minNegativesReady} negatives."
        )
      }

      val examples = buildList {
        positives.forEach { file ->
          add(FeatureExample(file = file, label = 1, feature = extractFeature(file)))
        }
        negatives.forEach { file ->
          add(FeatureExample(file = file, label = 0, feature = extractFeature(file)))
        }
      }
      if (examples.isEmpty()) {
        return ComputeLearnedResult.Error(message = "No training examples found.")
      }

      val split = stratifiedSplit(examples, validationFraction = 0.2, seed = 1337)
      if (split.train.isEmpty() || split.validation.isEmpty()) {
        return ComputeLearnedResult.Error(message = "Not enough examples for train/validation split.")
      }

      val trainX = split.train.map { it.feature }.toTypedArray()
      val trainY = split.train.map { it.label }.toIntArray()
      val validationX = split.validation.map { it.feature }.toTypedArray()
      val validationY = split.validation.map { it.label }.toIntArray()

      val normalization = computeNormalization(trainX)
      val normalizedTrainX = trainX.map { normalize(it, normalization) }.toTypedArray()
      val normalizedValidationX = validationX.map { normalize(it, normalization) }.toTypedArray()

      val model = trainLogisticRegression(
        x = normalizedTrainX,
        y = trainY,
        iterations = 700,
        learningRate = 0.06,
        l2Penalty = 1e-4,
      )

      val trainProbabilities = normalizedTrainX.map { feature -> predictProbability(model, feature) }.toDoubleArray()
      val validationProbabilities = normalizedValidationX.map { feature -> predictProbability(model, feature) }.toDoubleArray()

      val thresholdSelection = chooseThreshold(probabilities = validationProbabilities, labels = validationY)
      val trainMetrics = evaluateMetrics(trainProbabilities, trainY, thresholdSelection.threshold)
      val validationMetrics = thresholdSelection.metrics
      val qualityLabel = qualityLabelFromMetrics(validationMetrics)
      val learnedAtIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

      val modelJson = JSONObject().apply {
        put("model_type", "logistic_regression_linear")
        put("dataset_revision", profile.datasetRevision)
        put("trained_at", learnedAtIso)
        put("threshold", thresholdSelection.threshold)
        put("feature_extractor", JSONObject().apply {
          put("type", "log_mel_mean_std")
          put("sample_rate_hz", TrainingConfig.sampleRateHz)
          put("n_fft", FeatureSettings.nFft)
          put("frame_length", FeatureSettings.frameLength)
          put("hop_length", FeatureSettings.hopLength)
          put("mel_bins", FeatureSettings.melBins)
          put("f_min_hz", FeatureSettings.fMinHz)
          put("f_max_hz", FeatureSettings.fMaxHz)
          put("window", "hamming")
          put("aggregation", "mean_std_over_time")
        })
        put("normalization", JSONObject().apply {
          put("mean", normalization.mean.toJsonArray())
          put("std", normalization.std.toJsonArray())
        })
        put("weights", model.weights.toJsonArray())
        put("bias", model.bias)
      }

      val reportJson = JSONObject().apply {
        put("profile_id", profile.id)
        put("profile_name", profile.name)
        put("dataset_revision", profile.datasetRevision)
        put("computed_at", learnedAtIso)
        put("counts", JSONObject().apply {
          put("positives", positives.size)
          put("negatives", negatives.size)
          put("total_examples", positives.size + negatives.size)
        })
        put("split", JSONObject().apply {
          put("method", "stratified_random_80_20")
          put("seed", 1337)
          put("train_count", split.train.size)
          put("validation_count", split.validation.size)
        })
        put("metrics", JSONObject().apply {
          put("training", trainMetrics.toJson())
          put("validation", validationMetrics.toJson())
        })
        put("recommended_threshold", thresholdSelection.threshold)
        put("recommended_threshold_reason", "Selected the threshold that maximizes validation F1 with precision tie-break.")
        put("quality_label", qualityLabel)
      }

      storage.writeLearnedArtifacts(
        profileId = profile.id,
        modelJson = modelJson,
        reportJson = reportJson,
        learnedAtIso = learnedAtIso,
      )
      profilesRepository.markLearnedComputed(
        profileId = profile.id,
        learnedAtIso = learnedAtIso,
        learnedQuality = qualityLabel,
      )

      ComputeLearnedResult.Success(
        learningResult = LearningResult(
          learnedAtIso = learnedAtIso,
          qualityLabel = qualityLabel,
          recommendedThreshold = thresholdSelection.threshold,
          validationMetrics = validationMetrics,
        )
      )
    } catch (error: Throwable) {
      ComputeLearnedResult.Error(message = "Learning failed: ${error.message ?: "unknown error"}")
    }
  }

  private fun extractFeature(file: File): DoubleArray {
    val samples = WavCodec.readPcm16MonoWav(file)
    return LogMelFeatureExtractor.extract(samples)
  }
}

private object FeatureSettings {
  const val nFft = 512
  const val frameLength = 400
  const val hopLength = 160
  const val melBins = 32
  const val fMinHz = 40.0
  const val fMaxHz = 7_600.0
}

private object LogMelFeatureExtractor {
  fun extract(samples: ShortArray): DoubleArray {
    val normalized = samples.map { sample -> sample / 32768.0 }.toDoubleArray()
    val frames = frameSignal(normalized, FeatureSettings.frameLength, FeatureSettings.hopLength)
    val window = hammingWindow(FeatureSettings.frameLength)
    val melFilterBank = melFilterBank(
      melBins = FeatureSettings.melBins,
      nFft = FeatureSettings.nFft,
      sampleRateHz = TrainingConfig.sampleRateHz,
      fMinHz = FeatureSettings.fMinHz,
      fMaxHz = FeatureSettings.fMaxHz,
    )
    val nFreqBins = FeatureSettings.nFft / 2 + 1
    val logMelFrames = Array(frames.size) { DoubleArray(FeatureSettings.melBins) }

    frames.forEachIndexed { frameIndex, frame ->
      val real = DoubleArray(FeatureSettings.nFft)
      val imag = DoubleArray(FeatureSettings.nFft)
      for (i in frame.indices) {
        real[i] = frame[i] * window[i]
      }
      fftInPlace(real, imag)
      val powerSpectrum = DoubleArray(nFreqBins)
      for (bin in 0 until nFreqBins) {
        powerSpectrum[bin] = real[bin] * real[bin] + imag[bin] * imag[bin]
      }

      for (mel in 0 until FeatureSettings.melBins) {
        var energy = 0.0
        for (bin in 0 until nFreqBins) {
          energy += powerSpectrum[bin] * melFilterBank[mel][bin]
        }
        logMelFrames[frameIndex][mel] = ln(max(energy, 1e-9))
      }
    }

    val mean = DoubleArray(FeatureSettings.melBins)
    val std = DoubleArray(FeatureSettings.melBins)
    for (mel in 0 until FeatureSettings.melBins) {
      var sum = 0.0
      for (frame in logMelFrames.indices) {
        sum += logMelFrames[frame][mel]
      }
      val mu = sum / logMelFrames.size.toDouble()
      mean[mel] = mu

      var variance = 0.0
      for (frame in logMelFrames.indices) {
        val diff = logMelFrames[frame][mel] - mu
        variance += diff * diff
      }
      std[mel] = sqrt(variance / logMelFrames.size.toDouble()).coerceAtLeast(1e-6)
    }

    return DoubleArray(FeatureSettings.melBins * 2).also { feature ->
      for (i in 0 until FeatureSettings.melBins) {
        feature[i] = mean[i]
        feature[i + FeatureSettings.melBins] = std[i]
      }
    }
  }

  private fun frameSignal(signal: DoubleArray, frameLength: Int, hopLength: Int): Array<DoubleArray> {
    if (signal.isEmpty()) {
      return arrayOf(DoubleArray(frameLength))
    }
    val frameCount = max(1, 1 + ((signal.size - frameLength).coerceAtLeast(0) / hopLength))
    val frames = Array(frameCount) { DoubleArray(frameLength) }
    for (frameIndex in 0 until frameCount) {
      val start = frameIndex * hopLength
      for (i in 0 until frameLength) {
        val sampleIndex = start + i
        frames[frameIndex][i] = if (sampleIndex < signal.size) signal[sampleIndex] else 0.0
      }
    }
    return frames
  }

  private fun hammingWindow(length: Int): DoubleArray {
    if (length <= 1) {
      return DoubleArray(length) { 1.0 }
    }
    return DoubleArray(length) { index ->
      0.54 - 0.46 * cos((2.0 * PI * index) / (length - 1).toDouble())
    }
  }

  private fun melFilterBank(
    melBins: Int,
    nFft: Int,
    sampleRateHz: Int,
    fMinHz: Double,
    fMaxHz: Double,
  ): Array<DoubleArray> {
    val nFreqBins = nFft / 2 + 1
    val minMel = hzToMel(fMinHz)
    val maxMel = hzToMel(fMaxHz)
    val melPoints = DoubleArray(melBins + 2) { index ->
      minMel + (maxMel - minMel) * index / (melBins + 1).toDouble()
    }
    val hzPoints = melPoints.map { mel -> melToHz(mel) }
    val binPoints = hzPoints.map { hz ->
      ((nFft + 1) * hz / sampleRateHz.toDouble()).toInt().coerceIn(0, nFreqBins - 1)
    }

    return Array(melBins) { mel ->
      DoubleArray(nFreqBins).also { filter ->
        val left = binPoints[mel]
        val center = binPoints[mel + 1]
        val right = binPoints[mel + 2]
        for (bin in left until center) {
          filter[bin] = (bin - left).toDouble() / max(1, center - left).toDouble()
        }
        for (bin in center until right) {
          filter[bin] = (right - bin).toDouble() / max(1, right - center).toDouble()
        }
      }
    }
  }

  private fun hzToMel(hz: Double): Double = 2595.0 * ln(1.0 + hz / 700.0) / ln(10.0)

  private fun melToHz(mel: Double): Double = 700.0 * (exp((mel / 2595.0) * ln(10.0)) - 1.0)
}

private fun fftInPlace(real: DoubleArray, imag: DoubleArray) {
  val n = real.size
  var j = 0
  for (i in 1 until n) {
    var bit = n shr 1
    while (j and bit != 0) {
      j = j xor bit
      bit = bit shr 1
    }
    j = j xor bit
    if (i < j) {
      val tempReal = real[i]
      val tempImag = imag[i]
      real[i] = real[j]
      imag[i] = imag[j]
      real[j] = tempReal
      imag[j] = tempImag
    }
  }

  var len = 2
  while (len <= n) {
    val angle = -2.0 * PI / len.toDouble()
    val wLenCos = cos(angle)
    val wLenSin = kotlin.math.sin(angle)
    for (i in 0 until n step len) {
      var wReal = 1.0
      var wImag = 0.0
      for (k in 0 until len / 2) {
        val uReal = real[i + k]
        val uImag = imag[i + k]
        val vReal = real[i + k + len / 2] * wReal - imag[i + k + len / 2] * wImag
        val vImag = real[i + k + len / 2] * wImag + imag[i + k + len / 2] * wReal

        real[i + k] = uReal + vReal
        imag[i + k] = uImag + vImag
        real[i + k + len / 2] = uReal - vReal
        imag[i + k + len / 2] = uImag - vImag

        val nextWReal = wReal * wLenCos - wImag * wLenSin
        val nextWImag = wReal * wLenSin + wImag * wLenCos
        wReal = nextWReal
        wImag = nextWImag
      }
    }
    len = len shl 1
  }
}

private fun stratifiedSplit(
  examples: List<FeatureExample>,
  validationFraction: Double,
  seed: Int,
): SplitSet {
  val positives = examples.filter { it.label == 1 }.shuffled(Random(seed))
  val negatives = examples.filter { it.label == 0 }.shuffled(Random(seed + 1))

  val validationPositives = positives.take(max(1, (positives.size * validationFraction).roundToInt()))
  val validationNegatives = negatives.take(max(1, (negatives.size * validationFraction).roundToInt()))
  val trainPositives = positives.drop(validationPositives.size)
  val trainNegatives = negatives.drop(validationNegatives.size)

  val train = (trainPositives + trainNegatives).shuffled(Random(seed + 2))
  val validation = (validationPositives + validationNegatives).shuffled(Random(seed + 3))
  return SplitSet(train = train, validation = validation)
}

private fun computeNormalization(features: Array<DoubleArray>): NormalizationStats {
  val dimension = features.first().size
  val mean = DoubleArray(dimension)
  val std = DoubleArray(dimension)
  val n = features.size.toDouble()

  for (feature in features) {
    for (index in 0 until dimension) {
      mean[index] += feature[index]
    }
  }
  for (index in 0 until dimension) {
    mean[index] /= n
  }
  for (feature in features) {
    for (index in 0 until dimension) {
      val diff = feature[index] - mean[index]
      std[index] += diff * diff
    }
  }
  for (index in 0 until dimension) {
    std[index] = sqrt(std[index] / n).coerceAtLeast(1e-6)
  }
  return NormalizationStats(mean = mean, std = std)
}

private fun normalize(feature: DoubleArray, normalizationStats: NormalizationStats): DoubleArray {
  return DoubleArray(feature.size) { index ->
    (feature[index] - normalizationStats.mean[index]) / normalizationStats.std[index]
  }
}

private fun trainLogisticRegression(
  x: Array<DoubleArray>,
  y: IntArray,
  iterations: Int,
  learningRate: Double,
  l2Penalty: Double,
): LogisticModel {
  val sampleCount = x.size
  val dimension = x.first().size
  val weights = DoubleArray(dimension)
  var bias = 0.0

  repeat(iterations) {
    val gradientWeights = DoubleArray(dimension)
    var gradientBias = 0.0

    for (sampleIndex in 0 until sampleCount) {
      val probability = sigmoid(dot(weights, x[sampleIndex]) + bias)
      val error = probability - y[sampleIndex].toDouble()
      for (featureIndex in 0 until dimension) {
        gradientWeights[featureIndex] += error * x[sampleIndex][featureIndex]
      }
      gradientBias += error
    }

    for (featureIndex in 0 until dimension) {
      gradientWeights[featureIndex] = (gradientWeights[featureIndex] / sampleCount) + l2Penalty * weights[featureIndex]
      weights[featureIndex] -= learningRate * gradientWeights[featureIndex]
    }
    gradientBias /= sampleCount
    bias -= learningRate * gradientBias
  }

  return LogisticModel(weights = weights, bias = bias)
}

private fun predictProbability(model: LogisticModel, feature: DoubleArray): Double {
  return sigmoid(dot(model.weights, feature) + model.bias)
}

private fun chooseThreshold(probabilities: DoubleArray, labels: IntArray): ThresholdSelection {
  val candidateThresholds = buildList {
    add(0.5)
    probabilities.sorted().forEach { probability ->
      add(probability)
    }
  }.distinct()

  var bestThreshold = 0.5
  var bestMetrics = evaluateMetrics(probabilities, labels, bestThreshold)
  for (threshold in candidateThresholds) {
    val metrics = evaluateMetrics(probabilities, labels, threshold)
    val better = when {
      metrics.f1 > bestMetrics.f1 -> true
      metrics.f1 < bestMetrics.f1 -> false
      metrics.precision > bestMetrics.precision -> true
      metrics.precision < bestMetrics.precision -> false
      metrics.recall > bestMetrics.recall -> true
      metrics.recall < bestMetrics.recall -> false
      else -> threshold < bestThreshold
    }
    if (better) {
      bestThreshold = threshold
      bestMetrics = metrics
    }
  }
  return ThresholdSelection(threshold = bestThreshold, metrics = bestMetrics)
}

private fun evaluateMetrics(probabilities: DoubleArray, labels: IntArray, threshold: Double): LearningMetrics {
  var tp = 0
  var fp = 0
  var tn = 0
  var fn = 0
  for (index in labels.indices) {
    val prediction = if (probabilities[index] >= threshold) 1 else 0
    when {
      prediction == 1 && labels[index] == 1 -> tp += 1
      prediction == 1 && labels[index] == 0 -> fp += 1
      prediction == 0 && labels[index] == 0 -> tn += 1
      else -> fn += 1
    }
  }
  val total = max(1, labels.size)
  val accuracy = (tp + tn).toDouble() / total.toDouble()
  val precision = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp).toDouble()
  val recall = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn).toDouble()
  val f1 = if (precision + recall == 0.0) {
    0.0
  } else {
    2.0 * precision * recall / (precision + recall)
  }
  return LearningMetrics(
    accuracy = accuracy,
    precision = precision,
    recall = recall,
    f1 = f1,
  )
}

private fun qualityLabelFromMetrics(metrics: LearningMetrics): String {
  return when {
    metrics.f1 >= 0.85 && metrics.precision >= 0.85 && metrics.recall >= 0.75 -> "Good"
    metrics.f1 >= 0.65 && metrics.precision >= 0.60 && metrics.recall >= 0.55 -> "OK"
    else -> "Weak"
  }
}

private fun sigmoid(value: Double): Double = 1.0 / (1.0 + exp(-value))

private fun dot(weights: DoubleArray, feature: DoubleArray): Double {
  var sum = 0.0
  for (index in weights.indices) {
    sum += weights[index] * feature[index]
  }
  return sum
}

private fun DoubleArray.toJsonArray(): JSONArray = JSONArray().apply {
  this@toJsonArray.forEach { value -> put(value) }
}

private fun LearningMetrics.toJson(): JSONObject = JSONObject().apply {
  put("accuracy", accuracy)
  put("precision", precision)
  put("recall", recall)
  put("f1", f1)
}
