package com.example.chinese_flashcard.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chinese_flashcard.core.domain.SettingsRepository
import com.example.chinese_flashcard.core.domain.StudyRepository
import com.example.chinese_flashcard.core.domain.StudySettings
import com.example.chinese_flashcard.core.domain.TodaySummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ProfileUiState(
  val loading: Boolean = true,
  val saving: Boolean = false,
  val stored: StudySettings = StudySettings(),
  val draft: StudySettings = StudySettings(),
  val today: TodaySummary? = null,
  val error: String? = null,
  val saved: Boolean = false,
) {
  val dirty get() = draft != stored
}

sealed interface ProfileAction {
  data class Edit(val settings: StudySettings) : ProfileAction
  data object Save : ProfileAction
  data object Retry : ProfileAction
}
sealed interface ProfileMutation {
  data class Loaded(val settings: StudySettings, val today: TodaySummary) : ProfileMutation
  data class Edited(val settings: StudySettings) : ProfileMutation
  data object Saving : ProfileMutation
  data class Saved(val settings: StudySettings) : ProfileMutation
  data class Failed(val message: String) : ProfileMutation
  data object Idle : ProfileMutation
}
object ProfileReducer {
  fun reduce(state: ProfileUiState, mutation: ProfileMutation): ProfileUiState = when (mutation) {
    is ProfileMutation.Loaded -> state.copy(loading = false, stored = mutation.settings,
      draft = if (state.draft == state.stored) mutation.settings else state.draft,
      today = mutation.today, error = null)
    is ProfileMutation.Edited -> state.copy(draft = mutation.settings, saved = false, error = null)
    ProfileMutation.Saving -> state.copy(saving = true, saved = false, error = null)
    is ProfileMutation.Saved -> state.copy(stored = mutation.settings, draft = mutation.settings, saved = true)
    is ProfileMutation.Failed -> state.copy(loading = false, error = mutation.message, saved = false)
    ProfileMutation.Idle -> state.copy(saving = false)
  }
}

class ProfileViewModel(
  private val settings: SettingsRepository,
  private val study: StudyRepository,
) : ViewModel() {
  private val mutableState = MutableStateFlow(ProfileUiState())
  val state = mutableState.asStateFlow()
  private val operationLock = Mutex()
  private var observation: Job? = null
  private var failedSave = false
  init { observe() }

  fun onAction(action: ProfileAction) {
    when (action) {
      is ProfileAction.Edit -> if (!state.value.saving) mutate(ProfileMutation.Edited(action.settings))
      ProfileAction.Save -> save()
      ProfileAction.Retry -> if (failedSave) save() else observe()
    }
  }
  fun edit(value: StudySettings) = onAction(ProfileAction.Edit(value))
  fun save() {
    if (state.value.saving) return
    val value = state.value.draft
    mutate(ProfileMutation.Saving)
    viewModelScope.launch {
      try {
        operationLock.withLock {
          value.validate()
          withContext(Dispatchers.IO) { settings.save(value) }
          failedSave = false
          mutate(ProfileMutation.Saved(value))
        }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        failedSave = true
        mutate(ProfileMutation.Failed("Your settings couldn't be saved. Try again."))
      } finally {
        mutate(ProfileMutation.Idle)
      }
    }
  }
  fun retry() = onAction(ProfileAction.Retry)

  private fun mutate(mutation: ProfileMutation) {
    mutableState.update { ProfileReducer.reduce(it, mutation) }
  }
  private fun observe() {
    observation?.cancel()
    observation = viewModelScope.launch {
      try {
        combine(settings.settings, study.changes.onStart { emit(0L) }) { _, _ -> Unit }
          .collect {
            operationLock.withLock {
              val (today, value) = withContext(Dispatchers.IO) { study.snapshot().today to settings.settings.first() }
              mutate(ProfileMutation.Loaded(value, today))
            }
          }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        failedSave = false
        mutate(ProfileMutation.Failed("Your saved settings couldn't be loaded. Try again."))
      }
    }
  }
}
