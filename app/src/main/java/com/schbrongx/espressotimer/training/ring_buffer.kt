package com.schbrongx.espressotimer.training

import kotlin.math.roundToInt

class TimestampedRingBuffer(
  private val sampleRateHz: Int,
  private val retentionSeconds: Double,
) {
  private data class Chunk(
    val startMonotonicSec: Double,
    val samples: ShortArray,
    val sampleRateHz: Int,
  ) {
    val endMonotonicSec: Double
      get() = startMonotonicSec + samples.size.toDouble() / sampleRateHz.toDouble()
  }

  private val chunks = ArrayDeque<Chunk>()

  @Synchronized
  fun clear() {
    chunks.clear()
  }

  @Synchronized
  fun append(samples: ShortArray, chunkStartMonotonicSec: Double) {
    if (samples.isEmpty()) {
      return
    }
    chunks.addLast(
      Chunk(
        startMonotonicSec = chunkStartMonotonicSec,
        samples = samples,
        sampleRateHz = sampleRateHz,
      )
    )
    trim()
  }

  @Synchronized
  fun latestMonotonicSec(): Double? = chunks.lastOrNull()?.endMonotonicSec

  @Synchronized
  fun earliestMonotonicSec(): Double? = chunks.firstOrNull()?.startMonotonicSec

  @Synchronized
  fun bufferedDurationSeconds(): Double {
    if (chunks.isEmpty()) {
      return 0.0
    }
    return (chunks.last().endMonotonicSec - chunks.first().startMonotonicSec).coerceAtLeast(0.0)
  }

  @Synchronized
  fun coverageWindowSeconds(): Pair<Double, Double>? {
    if (chunks.isEmpty()) {
      return null
    }
    return chunks.first().startMonotonicSec to chunks.last().endMonotonicSec
  }

  @Synchronized
  fun extractWindow(windowStartSec: Double, windowEndSec: Double): ShortArray? {
    if (windowEndSec <= windowStartSec) {
      return null
    }
    if (chunks.isEmpty()) {
      return null
    }
    val earliest = chunks.first().startMonotonicSec
    val latest = chunks.last().endMonotonicSec
    if (windowStartSec < earliest || windowEndSec > latest) {
      return null
    }

    val outputSize = ((windowEndSec - windowStartSec) * sampleRateHz.toDouble()).roundToInt().coerceAtLeast(1)
    val output = ShortArray(outputSize)
    val filled = BooleanArray(outputSize)

    chunks.forEach { chunk ->
      val overlapStart = maxOf(windowStartSec, chunk.startMonotonicSec)
      val overlapEnd = minOf(windowEndSec, chunk.endMonotonicSec)
      if (overlapEnd <= overlapStart) {
        return@forEach
      }

      val srcStart = ((overlapStart - chunk.startMonotonicSec) * sampleRateHz.toDouble()).roundToInt()
      val dstStart = ((overlapStart - windowStartSec) * sampleRateHz.toDouble()).roundToInt()
      val overlapSamples = ((overlapEnd - overlapStart) * sampleRateHz.toDouble()).roundToInt()
      val maxSrcLength = chunk.samples.size - srcStart
      val maxDstLength = output.size - dstStart
      val copyLength = minOf(overlapSamples, maxSrcLength, maxDstLength)

      if (copyLength > 0 && srcStart >= 0 && dstStart >= 0) {
        System.arraycopy(chunk.samples, srcStart, output, dstStart, copyLength)
        for (idx in dstStart until (dstStart + copyLength).coerceAtMost(output.size)) {
          filled[idx] = true
        }
      }
    }

    val missingSamples = filled.count { isFilled -> !isFilled }
    return if (missingSamples == 0) {
      output
    } else {
      null
    }
  }

  @Synchronized
  fun hasWindow(windowStartSec: Double, windowEndSec: Double): Boolean {
    if (chunks.isEmpty()) {
      return false
    }
    val earliest = chunks.first().startMonotonicSec
    val latest = chunks.last().endMonotonicSec
    return windowStartSec >= earliest && windowEndSec <= latest
  }

  private fun trim() {
    if (chunks.isEmpty()) {
      return
    }
    val latestEnd = chunks.last().endMonotonicSec
    val keepAfter = latestEnd - retentionSeconds
    while (chunks.isNotEmpty() && chunks.first().endMonotonicSec < keepAfter) {
      chunks.removeFirst()
    }
  }
}
