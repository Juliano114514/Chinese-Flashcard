package com.example.chinese_flashcard.feature.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chinese_flashcard.core.domain.NewStudyDayException
import com.example.chinese_flashcard.core.domain.SettingsRepository
import com.example.chinese_flashcard.core.domain.StudyKind
import com.example.chinese_flashcard.core.domain.StudyRepository
import com.example.chinese_flashcard.core.domain.StudySettings
import com.example.chinese_flashcard.core.domain.StudySnapshot
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

data class WritingRequest(val token: Long, val sessionId: String)

data class StudyUiState(
  val loading: Boolean = true,
  val busy: Boolean = false,
  val settings: StudySettings = StudySettings(),
  val snapshot: StudySnapshot? = null,
  val error: String? = null,
  val dailyInvitation: Boolean = false,
  val writingRequest: WritingRequest? = null,
) {
  val today get() = snapshot?.today
  val card get() = snapshot?.card
}

sealed interface StudyAction {
  data object Refresh : StudyAction
  data object Retry : StudyAction
  data class Start(val kind: StudyKind) : StudyAction
  data class LearnMore(val expectedDate: String, val expectedNewPlanned: Int) : StudyAction
  data class Submit(val cardId: String, val optionId: String?) : StudyAction
  data class Advance(val cardId: String) : StudyAction
  data class Explain(val cardId: String) : StudyAction
  data class ManualWriting(val wordId: String) : StudyAction
  data object DailyWriting : StudyAction
  data object DismissInvitation : StudyAction
  data class ConsumeWriting(val token: Long) : StudyAction
}

sealed interface StudyMutation {
  data object Busy : StudyMutation
  data object Idle : StudyMutation
  data class Loaded(val snapshot: StudySnapshot, val settings: StudySettings? = null) : StudyMutation
  data class Failed(val message: String) : StudyMutation
  data class Invitation(val visible: Boolean) : StudyMutation
  data class WritingReady(val request: WritingRequest) : StudyMutation
  data class WritingConsumed(val token: Long) : StudyMutation
}

object StudyReducer {
  fun reduce(state: StudyUiState, mutation: StudyMutation): StudyUiState = when (mutation) {
    StudyMutation.Busy -> state.copy(busy = true, error = null)
    StudyMutation.Idle -> state.copy(busy = false)
    is StudyMutation.Loaded -> state.copy(loading = false, snapshot = mutation.snapshot,
      settings = mutation.settings ?: state.settings, error = null,
      dailyInvitation = state.dailyInvitation && state.snapshot?.today?.date == mutation.snapshot.today.date &&
        mutation.snapshot.today.allComplete)
    is StudyMutation.Failed -> state.copy(loading = false, error = mutation.message)
    is StudyMutation.Invitation -> state.copy(dailyInvitation = mutation.visible)
    is StudyMutation.WritingReady -> state.copy(writingRequest = mutation.request, dailyInvitation = false)
    is StudyMutation.WritingConsumed -> if (state.writingRequest?.token == mutation.token) {
      state.copy(writingRequest = null)
    } else state
  }
}

class StudyViewModel(
  private val study: StudyRepository,
  private val settings: SettingsRepository,
) : ViewModel() {
  private val mutableState = MutableStateFlow(StudyUiState())
  val state = mutableState.asStateFlow()
  private val operationLock = Mutex()
  private var observation: Job? = null
  private var failedAction: StudyAction = StudyAction.Refresh
  private var writingToken = 0L
  private var openedCardWriting: String? = null

  init { observe() }

  fun onAction(action: StudyAction) {
    when (action) {
      StudyAction.DismissInvitation -> mutate(StudyMutation.Invitation(false))
      is StudyAction.ConsumeWriting -> mutate(StudyMutation.WritingConsumed(action.token))
      StudyAction.Retry -> {
        if (observation?.isActive != true) observe()
        execute(failedAction)
      }
      else -> execute(action)
    }
  }

  fun start(kind: StudyKind) = onAction(StudyAction.Start(kind))
  fun learnMore() { state.value.today?.let { onAction(StudyAction.LearnMore(it.date, it.newPlanned)) } }
  fun refresh() = onAction(StudyAction.Refresh)
  fun retry() = onAction(StudyAction.Retry)
  fun advance() { state.value.card?.let { onAction(StudyAction.Advance(it.id)) } }
  fun submit(optionId: String?) { state.value.card?.let { onAction(StudyAction.Submit(it.id, optionId)) } }
  fun explain() { state.value.card?.let { onAction(StudyAction.Explain(it.id)) } }
  fun startManualWriting(wordId: String) = onAction(StudyAction.ManualWriting(wordId))
  fun startDailyWriting() = onAction(StudyAction.DailyWriting)
  fun consumeWriting(token: Long) = onAction(StudyAction.ConsumeWriting(token))
  fun dismissInvitation() = onAction(StudyAction.DismissInvitation)

  /** The shared VM survives navigation, so returning from tracing cannot open it again. */
  fun claimCardWriting(cardId: String, sessionId: String): Boolean {
    val key = "$cardId/$sessionId"
    if (openedCardWriting == key) return false
    openedCardWriting = key
    return true
  }

  private fun mutate(mutation: StudyMutation) {
    mutableState.update { StudyReducer.reduce(it, mutation) }
  }

  private fun observe() {
    observation?.cancel()
    observation = viewModelScope.launch {
      try {
        combine(settings.settings, study.changes.onStart { emit(0L) }) { _, _ -> Unit }
          .collect {
            operationLock.withLock {
              val (snapshot, value) = withContext(Dispatchers.IO) { study.snapshot() to settings.settings.first() }
              mutate(StudyMutation.Loaded(snapshot, value))
              offerDailyWriting(snapshot)
            }
          }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        failedAction = StudyAction.Refresh
        mutate(StudyMutation.Failed("Your saved progress couldn't be loaded. Try again."))
      }
    }
  }

  private fun execute(action: StudyAction) {
    // Set busy before launch: two quick taps cannot queue duplicate writes.
    if (state.value.busy) return
    mutate(StudyMutation.Busy)
    viewModelScope.launch {
      var stored = false
      try {
        operationLock.withLock {
          val snapshot = withContext(Dispatchers.IO) {
            when (action) {
              is StudyAction.Start -> study.start(action.kind)
              is StudyAction.LearnMore -> study.learnMore(action.expectedDate, action.expectedNewPlanned)
              is StudyAction.Submit -> study.submit(action.cardId, action.optionId)
              is StudyAction.Advance -> study.advance(action.cardId)
              is StudyAction.Explain -> study.explain(action.cardId)
              is StudyAction.ManualWriting -> {
                val id = study.startManualWriting(action.wordId)
                stored = true
                mutate(StudyMutation.WritingReady(WritingRequest(++writingToken, id)))
                study.snapshot()
              }
              StudyAction.DailyWriting -> {
                val id = study.startDailyWriting()
                stored = true
                if (id != null) mutate(StudyMutation.WritingReady(WritingRequest(++writingToken, id)))
                else mutate(StudyMutation.Invitation(false))
                study.snapshot()
              }
              else -> study.snapshot()
            }
          }
          stored = true
          mutate(StudyMutation.Loaded(snapshot))
          offerDailyWriting(snapshot)
        }
      } catch (error: CancellationException) {
        throw error
      } catch (_: NewStudyDayException) {
        failedAction = StudyAction.Refresh
        try {
          operationLock.withLock {
            mutate(StudyMutation.Loaded(withContext(Dispatchers.IO) { study.snapshot() }))
          }
        } catch (error: CancellationException) {
          throw error
        } catch (_: Exception) {
          // Preserve the last saved snapshot and keep retry available.
        }
        mutate(StudyMutation.Failed("A new day has started. Your daily plan has been refreshed."))
      } catch (_: Exception) {
        failedAction = if (stored) StudyAction.Refresh else action
        mutate(StudyMutation.Failed("This step couldn't be completed. Try again."))
      } finally {
        mutate(StudyMutation.Idle)
      }
    }
  }

  private suspend fun offerDailyWriting(snapshot: StudySnapshot) {
    if (!snapshot.today.allComplete || state.value.dailyInvitation) return
    try {
      if (withContext(Dispatchers.IO) { study.claimDailyInvitation() }) {
        mutate(StudyMutation.Invitation(true))
      }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      failedAction = StudyAction.Refresh
      mutate(StudyMutation.Failed("The handwriting invitation couldn't be prepared. Try again."))
    }
  }
}
