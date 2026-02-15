package com.schbrongx.espressotimer.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class TrainingStorageTest {

  @Test
  fun storageLayoutAndIndexAppend_areCreatedAndUpdated() {
    val root = tempRoot()
    try {
      val storage = TrainingStorage(File(root, "data"))
      val profileId = UUID.randomUUID().toString()
      storage.ensureProfileLayout(profileId)

      assertTrue(storage.getPositivesDir(profileId).exists())
      assertTrue(storage.getNegativesDir(profileId).exists())
      assertTrue(storage.getIndexFile(profileId).exists())

      val positive = ShortArray(TrainingConfig.sampleRateHz / 2) { idx -> idx.toShort() }
      storage.saveSample(
        SampleSaveRequest(
          profileId = profileId,
          profileName = "Test",
          label = SampleLabel.Positive,
          samples = positive,
          tapTimeWallIso = "2026-02-15T10:00:00Z",
          tapTimeMonotonicSec = 123.4,
          sourceDeviceInfo = "TestDevice",
          windowPreSeconds = 1.5,
          windowPostSeconds = 2.5,
        )
      )
      storage.saveSample(
        SampleSaveRequest(
          profileId = profileId,
          profileName = "Test",
          label = SampleLabel.Negative,
          samples = positive,
          tapTimeWallIso = "2026-02-15T10:00:05Z",
          tapTimeMonotonicSec = 128.4,
          sourceDeviceInfo = "TestDevice",
          windowLengthSeconds = 2.0,
        )
      )

      assertEquals(1, storage.countSamples(profileId, SampleLabel.Positive))
      assertEquals(1, storage.countSamples(profileId, SampleLabel.Negative))

      val indexLines = storage.getIndexFile(profileId).readLines().filter { it.isNotBlank() }
      assertEquals(2, indexLines.size)

      val positiveFileName = storage.listSampleWavFiles(profileId, SampleLabel.Positive).first().name
      assertTrue(positiveFileName.contains("_pos_"))
      assertTrue(positiveFileName.endsWith(".wav"))
    } finally {
      root.deleteRecursively()
    }
  }

  private fun tempRoot(): File {
    val dir = File(System.getProperty("java.io.tmpdir"), "espresso-storage-test-${UUID.randomUUID()}")
    dir.mkdirs()
    return dir
  }
}

