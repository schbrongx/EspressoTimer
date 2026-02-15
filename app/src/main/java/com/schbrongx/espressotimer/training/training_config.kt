package com.schbrongx.espressotimer.training

object TrainingConfig {
  const val sampleRateHz: Int = 16_000
  const val channels: Int = 1
  const val sampleWidthBytes: Int = 2

  const val positivePreRollSeconds: Double = 1.5
  const val positivePostRollSeconds: Double = 2.5
  const val negativeWindowSeconds: Double = 2.0
  const val negativeDistanceFromPositiveSeconds: Double = 3.0

  const val ringBufferSeconds: Double = 10.0
  const val tapDebounceSeconds: Double = 0.5
  const val minPositivesReady: Int = 20
  const val minNegativesReady: Int = 40

  const val maxNegativesPerSession: Int = 120
  const val targetNegativeMultiplier: Int = 2
}
