package com.example.chinese_flashcard.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chinese_flashcard.core.domain.CsvImportPreview
import com.example.chinese_flashcard.core.domain.CsvImportReport
import com.example.chinese_flashcard.core.domain.CsvImportRepository
import com.example.chinese_flashcard.core.domain.CsvSource
import com.example.chinese_flashcard.core.domain.SettingsRepository
import com.example.chinese_flashcard.core.domain.StudyRepository
import com.example.chinese_flashcard.core.domain.StudySettings
import com.example.chinese_flashcard.core.domain.TodaySummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
  val today: TodaySummary? = null,
  val error: String? = null,
  val savedRevision: Long = 0,
)

/** A single editor changes only its own field, using the latest stored settings. */
data class ProfileUpdate(
  val displayName: String? = null,
  val avatarId: String? = null,
  val dailyWords: Int? = null,
  val rounds: Int? = null,
  val reviewDays: List<Int>? = null,
) {
  fun applyTo(value: StudySettings): StudySettings = value.copy(
    displayName = displayName?.trim() ?: value.displayName,
    avatarId = avatarId ?: value.avatarId,
    dailyWords = dailyWords ?: value.dailyWords,
    rounds = rounds ?: value.rounds,
    reviewDays = reviewDays ?: value.reviewDays,
  )
}

sealed interface ProfileAction {
  data object BeginEdit : ProfileAction
  data class Save(val update: ProfileUpdate) : ProfileAction
  data object Retry : ProfileAction
}
sealed interface ProfileMutation {
  data class Loaded(val settings: StudySettings, val today: TodaySummary, val clearError: Boolean = true) : ProfileMutation
  data object Editing : ProfileMutation
  data object Saving : ProfileMutation
  data class Saved(val settings: StudySettings) : ProfileMutation
  data class Failed(val message: String) : ProfileMutation
  data object Idle : ProfileMutation
}
object ProfileReducer {
  fun reduce(state: ProfileUiState, mutation: ProfileMutation): ProfileUiState = when (mutation) {
    is ProfileMutation.Loaded -> state.copy(loading = false, stored = mutation.settings,
      today = mutation.today, error = if (mutation.clearError) null else state.error)
    ProfileMutation.Editing -> state.copy(error = null)
    ProfileMutation.Saving -> state.copy(saving = true, error = null)
    is ProfileMutation.Saved -> state.copy(stored = mutation.settings, error = null,
      savedRevision = state.savedRevision + 1)
    is ProfileMutation.Failed -> state.copy(loading = false, error = mutation.message)
    ProfileMutation.Idle -> state.copy(saving = false)
  }
}

enum class CsvImportStage { IDLE, READING, IMPORTING, DISCARDING }
data class CsvImportUiState(
  val open: Boolean = false,
  val stage: CsvImportStage = CsvImportStage.IDLE,
  val preview: CsvImportPreview? = null,
  val report: CsvImportReport? = null,
  val error: String? = null,
) {
  val busy: Boolean get() = stage != CsvImportStage.IDLE
  val canDismiss: Boolean get() = stage != CsvImportStage.IMPORTING && stage != CsvImportStage.DISCARDING
}
sealed interface CsvImportAction {
  data class Read(val source: CsvSource) : CsvImportAction
  data object Confirm : CsvImportAction
  data object Dismiss : CsvImportAction
}
sealed interface CsvImportMutation {
  data object Reading : CsvImportMutation
  data class Previewed(val preview: CsvImportPreview) : CsvImportMutation
  data object Importing : CsvImportMutation
  data class Imported(val report: CsvImportReport) : CsvImportMutation
  data class Failed(val message: String) : CsvImportMutation
  data object Discarding : CsvImportMutation
  data object Closed : CsvImportMutation
  data class Idle(val stage: CsvImportStage) : CsvImportMutation
}
object CsvImportReducer {
  fun reduce(state: CsvImportUiState, mutation: CsvImportMutation): CsvImportUiState = when (mutation) {
    CsvImportMutation.Reading -> CsvImportUiState(open = true, stage = CsvImportStage.READING)
    is CsvImportMutation.Previewed -> state.copy(preview = mutation.preview)
    CsvImportMutation.Importing -> state.copy(stage = CsvImportStage.IMPORTING, error = null)
    is CsvImportMutation.Imported -> state.copy(preview = null, report = mutation.report)
    is CsvImportMutation.Failed -> state.copy(preview = null, error = mutation.message)
    CsvImportMutation.Discarding -> state.copy(stage = CsvImportStage.DISCARDING)
    CsvImportMutation.Closed -> CsvImportUiState()
    is CsvImportMutation.Idle -> if (state.stage == mutation.stage) state.copy(stage = CsvImportStage.IDLE) else state
  }
}

class ProfileViewModel(
  private val settings: SettingsRepository,
  private val study: StudyRepository,
  private val csvImport: CsvImportRepository,
) : ViewModel() {
  private val mutableState = MutableStateFlow(ProfileUiState())
  val state = mutableState.asStateFlow()
  private val operationLock = Mutex()
  private var observation: Job? = null
  private var failedSave: ProfileUpdate? = null
  private val mutableImportState = MutableStateFlow(CsvImportUiState())
  val importState = mutableImportState.asStateFlow()
  private var importOperation: Job? = null
  private var previewId: String? = null
  init { observe() }

  fun onAction(action: ProfileAction) {
    when (action) {
      ProfileAction.BeginEdit -> if (!state.value.saving) {
        failedSave = null
        mutate(ProfileMutation.Editing)
      }
      is ProfileAction.Save -> persist(action.update)
      ProfileAction.Retry -> failedSave?.let(::save) ?: observe()
    }
  }
  fun beginEdit() = onAction(ProfileAction.BeginEdit)
  fun save(update: ProfileUpdate) = onAction(ProfileAction.Save(update))
  private fun persist(update: ProfileUpdate) {
    if (state.value.loading || state.value.saving) return
    mutate(ProfileMutation.Saving)
    viewModelScope.launch {
      try {
        operationLock.withLock {
          val value = withContext(Dispatchers.IO) {
            update.applyTo(settings.settings.first()).also {
              it.validate()
              settings.save(it)
            }
          }
          failedSave = null
          mutate(ProfileMutation.Saved(value))
        }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        failedSave = update
        mutate(ProfileMutation.Failed("Your settings couldn't be saved. Try again."))
      } finally {
        mutate(ProfileMutation.Idle)
      }
    }
  }
  fun retry() = onAction(ProfileAction.Retry)

  fun onImportAction(action: CsvImportAction) {
    when (action) {
      is CsvImportAction.Read -> readCsv(action.source)
      CsvImportAction.Confirm -> confirmCsvImport()
      CsvImportAction.Dismiss -> dismissCsvImport()
    }
  }
  fun readCsv(source: CsvSource) {
    if (importState.value.open || importState.value.busy) return
    mutateImport(CsvImportMutation.Reading)
    importOperation = viewModelScope.launch {
      try {
        // The repository owns the IO dispatch and cancellation-safe preview delivery.
        val preview = csvImport.preview(source)
        previewId = preview.previewId
        mutateImport(CsvImportMutation.Previewed(preview))
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        mutateImport(CsvImportMutation.Failed("The CSV couldn't be read. Check that it uses UTF-8, then choose the file again."))
      } finally {
        mutateImport(CsvImportMutation.Idle(CsvImportStage.READING))
      }
    }
  }
  fun confirmCsvImport() {
    val current = importState.value
    val preview = current.preview ?: return
    val id = previewId ?: return
    if (current.busy || !preview.canImport || preview.newWords == 0) return
    mutateImport(CsvImportMutation.Importing)
    importOperation = viewModelScope.launch {
      try {
        val report = csvImport.commit(id)
        discardPreview(id)
        previewId = null
        mutateImport(CsvImportMutation.Imported(report))
        observe()
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        discardPreview(id)
        previewId = null
        mutateImport(CsvImportMutation.Failed("The CSV couldn't be imported. Choose the file again to retry."))
      } finally {
        mutateImport(CsvImportMutation.Idle(CsvImportStage.IMPORTING))
      }
    }
  }
  fun dismissCsvImport() {
    if (!importState.value.open || !importState.value.canDismiss) return
    val pending = importOperation
    mutateImport(CsvImportMutation.Discarding)
    pending?.cancel()
    importOperation = viewModelScope.launch {
      try {
        pending?.join()
        previewId?.let { id -> discardPreview(id) }
        previewId = null
        mutateImport(CsvImportMutation.Closed)
      } catch (error: CancellationException) {
        throw error
      }
    }
  }
  private suspend fun discardPreview(id: String) {
    try { csvImport.discard(id) }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { /* Temporary previews are also removed on app startup. */ }
  }
  private fun mutateImport(mutation: CsvImportMutation) {
    mutableImportState.update { CsvImportReducer.reduce(it, mutation) }
  }
  override fun onCleared() {
    importOperation?.cancel()
    previewId?.let { id -> CoroutineScope(Dispatchers.IO).launch { discardPreview(id) } }
    super.onCleared()
  }

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
              mutate(ProfileMutation.Loaded(value, today, clearError = failedSave == null))
            }
          }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        mutate(ProfileMutation.Failed("Your saved settings couldn't be loaded. Try again."))
      }
    }
  }
}
