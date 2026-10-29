package com.example.chinese_flashcard.feature.writing

import com.example.chinese_flashcard.core.domain.UiMessageKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chinese_flashcard.core.domain.StrokePoint
import com.example.chinese_flashcard.core.domain.StrokeName
import com.example.chinese_flashcard.core.domain.WritingRepository
import com.example.chinese_flashcard.core.domain.WritingSnapshot
import com.example.chinese_flashcard.core.domain.WritingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WritingUiState(val loading: Boolean = true, val snapshot: WritingSnapshot? = null,
  val busy: Boolean = false, val error: UiMessageKey? = null, val saveError: UiMessageKey? = null,
  val strokeCue: WritingStrokeCue? = null)

data class WritingStrokeCue(val sequence: Long, val name: StrokeName)

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
  data class LoadFailed(val message: UiMessageKey) : WritingMutation
  data object Saving : WritingMutation
  data class SaveFailed(val message: UiMessageKey) : WritingMutation
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
  private var pendingStroke: Pair<WritingSnapshot, StrokeName?>? = null
  private var cueSequence = 0L

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
        pendingStroke = snapshot to snapshot.item?.strokeNames?.getOrNull(snapshot.accepted.size)
        write { repository.submitStroke(sessionId, cursor, points, snapshot.learningLanguage, snapshot.contextVersion) }
      }
      WritingAction.Undo -> if (snapshot.accepted.isNotEmpty()) write { repository.undo(sessionId, cursor, snapshot.learningLanguage, snapshot.contextVersion) }
      WritingAction.Restart -> write { repository.restartCharacter(sessionId, cursor, snapshot.learningLanguage, snapshot.contextVersion) }
      WritingAction.Skip -> write { repository.skip(sessionId, snapshot.learningLanguage, snapshot.contextVersion) }
      else -> Unit
    }
  }

  private fun load() {
    mutate(WritingMutation.Loading)
    viewModelScope.launch {
      try { mutate(WritingMutation.Loaded(repository.load(sessionId))) }
      catch (error: Exception) {
        if (error is CancellationException) throw error
        mutate(WritingMutation.LoadFailed(UiMessageKey.WRITING_PRACTICE_COULD_NOT_BE_LOADED_YOUR_SAVED_DRAFT_WAS_KEPT))
      }
    }
  }

  private fun write(operation: suspend () -> WritingSnapshot) {
    pending = operation
    mutate(WritingMutation.Saving)
    viewModelScope.launch {
      try {
        val saved = operation()
        val stroke = pendingStroke
        val before = stroke?.first
        val name = stroke?.second
        val accepted = before != null && saved.contextVersion == before.contextVersion &&
          saved.learningLanguage == before.learningLanguage && (saved.status == WritingStatus.COMPLETED ||
          saved.wordIndex > before.wordIndex || saved.characterIndex > before.characterIndex ||
          saved.accepted.size > before.accepted.size)
        pending = null
        pendingStroke = null
        mutate(WritingMutation.Loaded(saved))
        if (accepted && name != null) {
          val cue = WritingStrokeCue(++cueSequence, name)
          mutable.update { it.copy(strokeCue = cue) }
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        mutate(WritingMutation.SaveFailed(UiMessageKey.THE_DRAWING_COULD_NOT_BE_SAVED_RETRY_TO_CONTINUE_YOUR_PREVIOUS_DRAFT_WAS_KEPT))
      }
    }
  }
}
