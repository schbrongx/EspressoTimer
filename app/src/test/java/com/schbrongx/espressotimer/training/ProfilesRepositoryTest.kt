package com.schbrongx.espressotimer.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ProfilesRepositoryTest {

  @Test
  fun profileCrud_createRenameSelectDeleteAndReset() {
    val root = tempRoot()
    try {
      val storage = TrainingStorage(File(root, "data"))
      val repository = ProfilesRepository(storage)

      val created = repository.createProfile("Dial-In")
      assertEquals(1, created.profiles.size)
      val profile = created.profiles.first()
      assertEquals("Dial-In", profile.name)
      assertEquals(profile.id, created.activeProfileId)
      assertFalse(profile.hasData)

      repository.renameProfile(profile.id, "Morning")
      val renamed = repository.getState().profiles.first()
      assertEquals("Morning", renamed.name)

      repository.addSample(profile.id, SampleLabel.Positive, durationSeconds = 4.0, eventTimeWallIso = "2026-02-15T10:00:00Z")
      repository.addSample(profile.id, SampleLabel.Negative, durationSeconds = 2.0, eventTimeWallIso = "2026-02-15T10:00:04Z")
      val withData = repository.getState().profiles.first()
      assertTrue(withData.hasData)
      assertEquals(1, withData.positivesCount)
      assertEquals(1, withData.negativesCount)
      assertEquals(6.0, withData.totalRecordedSeconds, 0.0001)
      assertNotNull(withData.lastEventWallIso)

      repository.resetTrainingData(profile.id)
      val reset = repository.getState().profiles.first()
      assertFalse(reset.hasData)
      assertEquals(0, reset.positivesCount)
      assertEquals(0, reset.negativesCount)
      assertEquals(0.0, reset.totalRecordedSeconds, 0.0001)

      val second = repository.createProfile("Second").profiles.last()
      repository.selectActiveProfile(second.id)
      assertEquals(second.id, repository.getState().activeProfileId)

      repository.deleteProfile(second.id)
      val afterDelete = repository.getState()
      assertEquals(1, afterDelete.profiles.size)
      assertEquals(profile.id, afterDelete.profiles.first().id)
    } finally {
      root.deleteRecursively()
    }
  }

  private fun tempRoot(): File {
    val dir = File(System.getProperty("java.io.tmpdir"), "espresso-training-test-${UUID.randomUUID()}")
    dir.mkdirs()
    return dir
  }
}

