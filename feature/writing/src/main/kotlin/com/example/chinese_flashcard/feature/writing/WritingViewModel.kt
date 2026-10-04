package com.example.chinese_flashcard.feature.writing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chinese_flashcard.core.domain.StrokePoint
import com.example.chinese_flashcard.core.domain.WritingRepository
import com.example.chinese_flashcard.core.domain.WritingSnapshot
import com.example.chinese_flashcard.core.domain.WritingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WritingUiState(val loading: Boolean = true, val snapshot: WritingSnapshot? = null,
  val busy: Boolean = false, val error: String? = null, val saveError: String? = null)

sealed interface WritingAction {
  data object Retry : WritingAction
  data object RetrySave : WritingAction
  data class Stroke(val points: List<StrokePoint>) : WritingAction
  data object Undo : WritingAction
  data object Restart : WritingAction
  data object Skip : WritingAction
}

sealed interface WritingMutation {
  data object Loading : WritingMutation
  data class Loaded(val snapshot: WritingSnapshot) : WritingMutation
  data class LoadFailed(val message: String) : WritingMutation
  data object Saving : WritingMutation
  data class SaveFailed(val message: String) : WritingMutation
}

object WritingReducer {
  fun reduce(state: WritingUiState, mutation: WritingMutation): WritingUiState = when (mutation) {
    WritingMutation.Loading -> state.copy(loading = true, error = null)
    is WritingMutation.Loaded -> state.copy(loading = false, snapshot = mutation.snapshot,
      busy = false, error = null, saveError = null)
    is WritingMutation.LoadFailed -> state.copy(loading = false, error = mutation.message)
    WritingMutation.Saving -> state.copy(busy = true, saveError = null)
    is WritingMutation.SaveFailed -> state.copy(busy = false, saveError = mutation.message)
  }
}

/** Room is the source of both the drawing draft and the current word/character position. */
class WritingViewModel(private val sessionId: String, private val repository: WritingRepository) : ViewModel() {
  private val mutable = MutableStateFlow(WritingUiState())
  val state = mutable.asStateFlow()
  private var pending: (suspend () -> WritingSnapshot)? = null

  init { load() }

  private fun mutate(mutation: WritingMutation) {
    mutable.update { WritingReducer.reduce(it, mutation) }
  }

  fun onAction(action: WritingAction) {
    val current = state.value
    if (current.busy) return
    if (action == WritingAction.Retry) {
      if (!current.loading) load()
      return
    }
    if (current.loading || current.error != null) return
    if (action == WritingAction.RetrySave) {
      pending?.let(::write)
      return
    }
    if (current.saveError != null) return
    val snapshot = current.snapshot ?: return
    if (snapshot.status != WritingStatus.ACTIVE) return
    val cursor = snapshot.cursor
    when (action) {
      is WritingAction.Stroke -> {
        val points = action.points.take(1024).toList()
        write { repository.submitStroke(sessionId, cursor, points) }
      }
      WritingAction.Undo -> if (snapshot.accepted.isNotEmpty()) write { repository.undo(sessionId, cursor) }
      WritingAction.Restart -> write { repository.restartCharacter(sessionId, cursor) }
      WritingAction.Skip -> write { repository.skip(sessionId) }
      else -> Unit
    }
  }

  private fun load() {
    mutate(WritingMutation.Loading)
    viewModelScope.launch {
      try { mutate(WritingMutation.Loaded(repository.load(sessionId))) }
      catch (error: Exception) {
        if (error is CancellationException) throw error
        mutate(WritingMutation.LoadFailed("Writing practice could not be loaded. Your saved draft was kept."))
      }
    }
  }

  private fun write(operation: suspend () -> WritingSnapshot) {
    pending = operation
    mutate(WritingMutation.Saving)
    viewModelScope.launch {
      try {
        val saved = operation()
        pending = null
        mutate(WritingMutation.Loaded(saved))
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        mutate(WritingMutation.SaveFailed("The drawing could not be saved. Retry to continue; your previous draft was kept."))
      }
    }
  }
}
