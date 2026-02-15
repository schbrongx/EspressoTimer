package com.schbrongx.espressotimer.training

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

data class TrainingProfile(
  val id: String,
  val name: String,
  val createdAtIso: String,
  val updatedAtIso: String,
  val positivesCount: Int,
  val negativesCount: Int,
  val totalRecordedSeconds: Double,
  val lastEventWallIso: String?,
  val datasetRevision: Long,
) {
  val hasData: Boolean
    get() = positivesCount > 0 || negativesCount > 0

  val isDatasetGuidanceReady: Boolean
    get() = positivesCount >= TrainingConfig.minPositivesReady && negativesCount >= TrainingConfig.minNegativesReady
}

data class ProfilesState(
  val activeProfileId: String?,
  val profiles: List<TrainingProfile>,
) {
  val activeProfile: TrainingProfile?
    get() = profiles.firstOrNull { it.id == activeProfileId }
}

class ProfilesRepository(private val storage: TrainingStorage) {
  private val lock = Any()
  private var cachedState: ProfilesState? = null

  fun getState(): ProfilesState {
    synchronized(lock) {
      if (cachedState == null) {
        cachedState = readStateFromDisk()
      }
      return cachedState ?: ProfilesState(activeProfileId = null, profiles = emptyList())
    }
  }

  fun createProfile(name: String): ProfilesState {
    synchronized(lock) {
      val now = nowIso()
      val state = getState()
      val profile = TrainingProfile(
        id = UUID.randomUUID().toString(),
        name = name.trim(),
        createdAtIso = now,
        updatedAtIso = now,
        positivesCount = 0,
        negativesCount = 0,
        totalRecordedSeconds = 0.0,
        lastEventWallIso = null,
        datasetRevision = 0L,
      )
      storage.ensureProfileLayout(profile.id)
      val nextState = state.copy(
        activeProfileId = state.activeProfileId ?: profile.id,
        profiles = (state.profiles + profile).sortedBy { it.createdAtIso }
      )
      persist(nextState)
      return nextState
    }
  }

  fun renameProfile(profileId: String, newName: String): ProfilesState {
    synchronized(lock) {
      val state = getState()
      val nextProfiles = state.profiles.map { profile ->
        if (profile.id == profileId) {
          profile.copy(name = newName.trim(), updatedAtIso = nowIso())
        } else {
          profile
        }
      }
      val nextState = state.copy(profiles = nextProfiles)
      persist(nextState)
      return nextState
    }
  }

  fun selectActiveProfile(profileId: String): ProfilesState {
    synchronized(lock) {
      val state = getState()
      if (state.profiles.none { it.id == profileId }) {
        return state
      }
      val nextState = state.copy(activeProfileId = profileId)
      persist(nextState)
      return nextState
    }
  }

  fun deleteProfile(profileId: String): ProfilesState {
    synchronized(lock) {
      val state = getState()
      val remainingProfiles = state.profiles.filterNot { it.id == profileId }
      val nextActive = when {
        state.activeProfileId == profileId -> remainingProfiles.firstOrNull()?.id
        else -> state.activeProfileId
      }

      storage.deleteProfileTrainingData(profileId)

      val nextState = ProfilesState(activeProfileId = nextActive, profiles = remainingProfiles)
      persist(nextState)
      return nextState
    }
  }

  fun resetTrainingData(profileId: String): ProfilesState {
    synchronized(lock) {
      val state = getState()
      storage.resetProfileTrainingData(profileId)
      val nextProfiles = state.profiles.map { profile ->
        if (profile.id == profileId) {
          profile.copy(
            positivesCount = 0,
            negativesCount = 0,
            totalRecordedSeconds = 0.0,
            lastEventWallIso = null,
            datasetRevision = profile.datasetRevision + 1L,
            updatedAtIso = nowIso(),
          )
        } else {
          profile
        }
      }
      val nextState = state.copy(profiles = nextProfiles)
      persist(nextState)
      return nextState
    }
  }

  fun addSample(
    profileId: String,
    label: SampleLabel,
    durationSeconds: Double,
    eventTimeWallIso: String,
  ): ProfilesState {
    synchronized(lock) {
      val state = getState()
      val nextProfiles = state.profiles.map { profile ->
        if (profile.id == profileId) {
          val updatedPositives = profile.positivesCount + if (label == SampleLabel.Positive) 1 else 0
          val updatedNegatives = profile.negativesCount + if (label == SampleLabel.Negative) 1 else 0
          profile.copy(
            positivesCount = updatedPositives,
            negativesCount = updatedNegatives,
            totalRecordedSeconds = profile.totalRecordedSeconds + durationSeconds.coerceAtLeast(0.0),
            lastEventWallIso = eventTimeWallIso,
            datasetRevision = profile.datasetRevision + 1L,
            updatedAtIso = nowIso(),
          )
        } else {
          profile
        }
      }
      val nextState = state.copy(profiles = nextProfiles)
      persist(nextState)
      return nextState
    }
  }

  private fun persist(state: ProfilesState) {
    val root = JSONObject().apply {
      put("active_profile_id", state.activeProfileId ?: JSONObject.NULL)
      put("profiles", JSONArray().apply {
        state.profiles.forEach { profile ->
          put(profile.toJson())
        }
      })
    }
    storage.atomicWriteText(storage.getProfilesFile(), root.toString(2))
    cachedState = state
  }

  private fun readStateFromDisk(): ProfilesState {
    val file = storage.getProfilesFile()
    if (!file.exists()) {
      val empty = ProfilesState(activeProfileId = null, profiles = emptyList())
      persist(empty)
      return empty
    }

    val json = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    val activeProfileId = json.optString("active_profile_id", "").ifBlank { null }
    val profiles = mutableListOf<TrainingProfile>()
    val profilesArray = json.optJSONArray("profiles") ?: JSONArray()
    for (index in 0 until profilesArray.length()) {
      val profileJson = profilesArray.optJSONObject(index) ?: continue
      val profileId = profileJson.optString("id")
      if (profileId.isBlank()) {
        continue
      }
      profiles += TrainingProfile(
        id = profileId,
        name = profileJson.optString("name"),
        createdAtIso = profileJson.optString("created_at"),
        updatedAtIso = profileJson.optString("updated_at"),
        positivesCount = profileJson.optInt("positives_count"),
        negativesCount = profileJson.optInt("negatives_count"),
        totalRecordedSeconds = profileJson.optDouble("total_recorded_seconds", 0.0),
        lastEventWallIso = profileJson.optString("last_event_wall", "").ifBlank { null },
        datasetRevision = profileJson.optLong("dataset_revision"),
      )
      storage.ensureProfileLayout(profileId)
    }

    val finalActive = when {
      activeProfileId != null && profiles.any { it.id == activeProfileId } -> activeProfileId
      else -> profiles.firstOrNull()?.id
    }
    return ProfilesState(activeProfileId = finalActive, profiles = profiles)
  }
}

private fun TrainingProfile.toJson(): JSONObject {
  return JSONObject().apply {
    put("id", id)
    put("name", name)
    put("created_at", createdAtIso)
    put("updated_at", updatedAtIso)
    put("positives_count", positivesCount)
    put("negatives_count", negativesCount)
    put("total_recorded_seconds", totalRecordedSeconds)
    put("last_event_wall", lastEventWallIso ?: JSONObject.NULL)
    put("dataset_revision", datasetRevision)
  }
}

private fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

