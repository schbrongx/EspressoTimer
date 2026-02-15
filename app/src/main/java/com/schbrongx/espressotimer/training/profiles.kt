package com.schbrongx.espressotimer.training

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class LearningQualityLabel(val value: String) {
  Good("Good"),
  OK("OK"),
  Weak("Weak");

  companion object {
    fun fromValue(raw: String?): LearningQualityLabel? {
      return values().firstOrNull { quality -> quality.value.equals(raw?.trim(), ignoreCase = true) }
    }
  }
}

enum class ProfileLearningStatus {
  NotReady,
  Ready,
  Learned,
  Outdated,
}

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
  val datasetHash: String,
  val learnedDatasetRevision: Long?,
  val learnedDatasetHash: String?,
  val learnedAtIso: String?,
  val learnedQualityLabel: LearningQualityLabel?,
) {
  val hasData: Boolean
    get() = positivesCount > 0 || negativesCount > 0

  val isDatasetGuidanceReady: Boolean
    get() = positivesCount >= TrainingConfig.minPositivesReady && negativesCount >= TrainingConfig.minNegativesReady

  val learningStatus: ProfileLearningStatus
    get() {
      if (!isDatasetGuidanceReady) {
        return ProfileLearningStatus.NotReady
      }
      if (!hasLearnedArtifactReference) {
        return ProfileLearningStatus.Ready
      }
      val revisionMatches = learnedDatasetRevision != null && learnedDatasetRevision == datasetRevision
      val hashMatches = !learnedDatasetHash.isNullOrBlank() && learnedDatasetHash == datasetHash
      return if (revisionMatches && hashMatches) {
        ProfileLearningStatus.Learned
      } else {
        ProfileLearningStatus.Outdated
      }
    }

  val hasLearnedArtifactReference: Boolean
    get() = learnedDatasetRevision != null || !learnedDatasetHash.isNullOrBlank() || !learnedAtIso.isNullOrBlank()
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
      val profileId = UUID.randomUUID().toString()
      storage.ensureProfileLayout(profileId)
      val profile = TrainingProfile(
        id = profileId,
        name = name.trim(),
        createdAtIso = now,
        updatedAtIso = now,
        positivesCount = 0,
        negativesCount = 0,
        totalRecordedSeconds = 0.0,
        lastEventWallIso = null,
        datasetRevision = 0L,
        datasetHash = storage.computeDatasetHash(profileId),
        learnedDatasetRevision = null,
        learnedDatasetHash = null,
        learnedAtIso = null,
        learnedQualityLabel = null,
      )
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
      val resetDatasetHash = storage.computeDatasetHash(profileId)
      val nextProfiles = state.profiles.map { profile ->
        if (profile.id == profileId) {
          profile.copy(
            positivesCount = 0,
            negativesCount = 0,
            totalRecordedSeconds = 0.0,
            lastEventWallIso = null,
            datasetRevision = profile.datasetRevision + 1L,
            datasetHash = resetDatasetHash,
            learnedDatasetRevision = null,
            learnedDatasetHash = null,
            learnedAtIso = null,
            learnedQualityLabel = null,
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
    sampleSha256: String? = null,
    sampleId: String? = null,
  ): ProfilesState {
    synchronized(lock) {
      val state = getState()
      val nextProfiles = state.profiles.map { profile ->
        if (profile.id == profileId) {
          val updatedPositives = profile.positivesCount + if (label == SampleLabel.Positive) 1 else 0
          val updatedNegatives = profile.negativesCount + if (label == SampleLabel.Negative) 1 else 0
          val updatedDatasetHash = sampleSha256?.let { sha ->
            updateDatasetHash(
              previousHash = profile.datasetHash,
              label = label,
              sampleSha = sha,
              sampleId = sampleId,
              tapTimeWallIso = eventTimeWallIso,
            )
          } ?: storage.computeDatasetHash(profile.id)
          profile.copy(
            positivesCount = updatedPositives,
            negativesCount = updatedNegatives,
            totalRecordedSeconds = profile.totalRecordedSeconds + durationSeconds.coerceAtLeast(0.0),
            lastEventWallIso = eventTimeWallIso,
            datasetRevision = profile.datasetRevision + 1L,
            datasetHash = updatedDatasetHash,
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

  fun markProfileLearned(
    profileId: String,
    learnedDatasetRevision: Long,
    learnedDatasetHash: String,
    learnedAtIso: String,
    qualityLabel: LearningQualityLabel,
  ): ProfilesState {
    synchronized(lock) {
      val state = getState()
      val nextProfiles = state.profiles.map { profile ->
        if (profile.id == profileId) {
          profile.copy(
            learnedDatasetRevision = learnedDatasetRevision,
            learnedDatasetHash = learnedDatasetHash,
            learnedAtIso = learnedAtIso,
            learnedQualityLabel = qualityLabel,
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
      storage.ensureProfileLayout(profileId)
      val parsedProfile = TrainingProfile(
        id = profileId,
        name = profileJson.optString("name"),
        createdAtIso = profileJson.optString("created_at"),
        updatedAtIso = profileJson.optString("updated_at"),
        positivesCount = profileJson.optInt("positives_count"),
        negativesCount = profileJson.optInt("negatives_count"),
        totalRecordedSeconds = profileJson.optDouble("total_recorded_seconds", 0.0),
        lastEventWallIso = profileJson.optString("last_event_wall", "").ifBlank { null },
        datasetRevision = profileJson.optLong("dataset_revision"),
        datasetHash = profileJson.optString("dataset_hash", "").ifBlank { storage.computeDatasetHash(profileId) },
        learnedDatasetRevision = profileJson.optNullableLong("learned_dataset_revision"),
        learnedDatasetHash = profileJson.optString("learned_dataset_hash", "").ifBlank { null },
        learnedAtIso = profileJson.optString("learned_at", "").ifBlank { null },
        learnedQualityLabel = LearningQualityLabel.fromValue(profileJson.optString("learned_quality", "")),
      )
      profiles += hydrateLearnedMetadata(parsedProfile)
    }

    val finalActive = when {
      activeProfileId != null && profiles.any { it.id == activeProfileId } -> activeProfileId
      else -> profiles.firstOrNull()?.id
    }
    return ProfilesState(activeProfileId = finalActive, profiles = profiles)
  }

  private fun hydrateLearnedMetadata(profile: TrainingProfile): TrainingProfile {
    val artifactMetadata = storage.readLearnedArtifactMetadata(profile.id)
    if (artifactMetadata == null) {
      return profile.copy(
        learnedDatasetRevision = null,
        learnedDatasetHash = null,
        learnedAtIso = null,
        learnedQualityLabel = null,
      )
    }
    return profile.copy(
      learnedDatasetRevision = artifactMetadata.learnedDatasetRevision ?: profile.learnedDatasetRevision,
      learnedDatasetHash = artifactMetadata.learnedDatasetHash ?: profile.learnedDatasetHash,
      learnedAtIso = artifactMetadata.learnedAtIso ?: profile.learnedAtIso,
      learnedQualityLabel = artifactMetadata.qualityLabel ?: profile.learnedQualityLabel,
    )
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
    put("dataset_hash", datasetHash)
    put("learned_dataset_revision", learnedDatasetRevision ?: JSONObject.NULL)
    put("learned_dataset_hash", learnedDatasetHash ?: JSONObject.NULL)
    put("learned_at", learnedAtIso ?: JSONObject.NULL)
    put("learned_quality", learnedQualityLabel?.value ?: JSONObject.NULL)
  }
}

private fun JSONObject.optNullableLong(key: String): Long? {
  return if (has(key) && !isNull(key)) {
    optLong(key)
  } else {
    null
  }
}

private fun updateDatasetHash(
  previousHash: String,
  label: SampleLabel,
  sampleSha: String,
  sampleId: String?,
  tapTimeWallIso: String,
): String {
  return sha256OfStrings(
    listOf(
      previousHash,
      label.value,
      sampleSha,
      sampleId ?: "",
      tapTimeWallIso,
    )
  )
}

private fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
