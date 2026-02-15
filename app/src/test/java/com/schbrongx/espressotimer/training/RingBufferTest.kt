package com.schbrongx.espressotimer.training

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RingBufferTest {

  @Test
  fun extractWindow_returnsDeterministicSliceFromSyntheticAudio() {
    val buffer = TimestampedRingBuffer(sampleRateHz = 10, retentionSeconds = 10.0)
    val first = ShortArray(10) { index -> index.toShort() }      // 0.0s .. 1.0s
    val second = ShortArray(10) { index -> (index + 10).toShort() } // 1.0s .. 2.0s

    buffer.append(first, chunkStartMonotonicSec = 0.0)
    buffer.append(second, chunkStartMonotonicSec = 1.0)

    val extracted = buffer.extractWindow(windowStartSec = 0.5, windowEndSec = 1.5)
    assertNotNull(extracted)
    assertArrayEquals(shortArrayOf(5, 6, 7, 8, 9, 10, 11, 12, 13, 14), extracted)

    assertEquals(2.0, buffer.bufferedDurationSeconds(), 0.0001)
    assertEquals(Pair(0.0, 2.0), buffer.coverageWindowSeconds())
  }

  @Test
  fun extractWindow_returnsNullWhenOutsideCoverage() {
    val buffer = TimestampedRingBuffer(sampleRateHz = 10, retentionSeconds = 10.0)
    buffer.append(ShortArray(10) { index -> index.toShort() }, chunkStartMonotonicSec = 0.0)

    assertNull(buffer.extractWindow(windowStartSec = -0.2, windowEndSec = 0.2))
    assertNull(buffer.extractWindow(windowStartSec = 0.9, windowEndSec = 1.3))
  }
}

