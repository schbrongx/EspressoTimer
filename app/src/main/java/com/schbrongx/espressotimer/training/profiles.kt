package com.schbrongx.espressotimer.training

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class ProfileStatusBadge {
  NotReady,
  Ready,
  Learned,
  Outdated
}

data class TrainingProfile(
  val id: String,
  val name: String,
  val createdAtIso: String,
  val updatedAtIso: String,
  val positivesCount: Int,
  val negativesCount: Int,
  val datasetRevision: Long,
  val learnedDatasetRevision: Long?,
  val learnedAtIso: String?,
  val learnedQuality: String?,
) {
  val isReady: Boolean
    get() = positivesCount >= TrainingConfig.minPositivesReady && negativesCount >= TrainingConfig.minNegativesReady

  val hasLearnedArtifact: Boolean
    get() = learnedDatasetRevision != null && !learnedAtIso.isNullOrBlank()

  val isOutdated: Boolean
    get() = hasLearnedArtifact && learnedDatasetRevision != datasetRevision

  val statusBadge: ProfileStatusBadge
    get() = when {
      isOutdated -> ProfileStatusBadge.Outdated
      hasLearnedArtifact -> ProfileStatusBadge.Learned
      isReady -> ProfileStatusBadge.Ready
      else -> ProfileStatusBadge.NotReady
    }
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
        id = "profile_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}",
        name = name.trim(),
        createdAtIso = now,
        updatedAtIso = now,
        positivesCount = 0,
        negativesCount = 0,
        datasetRevision = 0L,
        learnedDatasetRevision = null,
        learnedAtIso = null,
        learnedQuality = null,
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
            datasetRevision = profile.datasetRevision + 1L,
            learnedDatasetRevision = null,
            learnedAtIso = null,
            learnedQuality = null,
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

  fun addSample(profileId: String, label: SampleLabel): ProfilesState {
    synchronized(lock) {
      val state = getState()
      val nextProfiles = state.profiles.map { profile ->
        if (profile.id == profileId) {
          val updatedPositives = profile.positivesCount + if (label == SampleLabel.Positive) 1 else 0
          val updatedNegatives = profile.negativesCount + if (label == SampleLabel.Negative) 1 else 0
          profile.copy(
            positivesCount = updatedPositives,
            negativesCount = updatedNegatives,
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

  fun markLearnedComputed(
    profileId: String,
    learnedAtIso: String,
    learnedQuality: String,
  ): ProfilesState {
    synchronized(lock) {
      val state = getState()
      val nextProfiles = state.profiles.map { profile ->
        if (profile.id == profileId) {
          profile.copy(
            learnedDatasetRevision = profile.datasetRevision,
            learnedAtIso = learnedAtIso,
            learnedQuality = learnedQuality,
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

  fun refreshLearnedMetadata(profileId: String): ProfilesState {
    synchronized(lock) {
      val state = getState()
      val report = storage.readLearnedReport(profileId)
      val learnedAt = storage.readLearnedAt(profileId)
      val learnedRevision = if (report != null && report.has("dataset_revision") && !report.isNull("dataset_revision")) {
        report.optLong("dataset_revision")
      } else {
        null
      }
      val quality = report?.optString("quality_label")?.takeIf { it.isNotBlank() }
      val nextProfiles = state.profiles.map { profile ->
        if (profile.id == profileId) {
          profile.copy(
            learnedDatasetRevision = learnedRevision,
            learnedAtIso = learnedAt,
            learnedQuality = quality,
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
      val learnedDatasetRevision = if (profileJson.has("learned_dataset_revision") && !profileJson.isNull("learned_dataset_revision")) {
        profileJson.optLong("learned_dataset_revision")
      } else {
        null
      }
      profiles += TrainingProfile(
        id = profileId,
        name = profileJson.optString("name"),
        createdAtIso = profileJson.optString("created_at"),
        updatedAtIso = profileJson.optString("updated_at"),
        positivesCount = profileJson.optInt("positives_count"),
        negativesCount = profileJson.optInt("negatives_count"),
        datasetRevision = profileJson.optLong("dataset_revision"),
        learnedDatasetRevision = learnedDatasetRevision,
        learnedAtIso = profileJson.optString("learned_at", "").ifBlank { null },
        learnedQuality = profileJson.optString("learned_quality", "").ifBlank { null },
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
    put("dataset_revision", datasetRevision)
    if (learnedDatasetRevision != null) {
      put("learned_dataset_revision", learnedDatasetRevision)
    }
    if (learnedAtIso != null) {
      put("learned_at", learnedAtIso)
    }
    if (learnedQuality != null) {
      put("learned_quality", learnedQuality)
    }
  }
}

private fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
