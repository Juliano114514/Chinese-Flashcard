package com.example.chinese_flashcard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chinese_flashcard.core.data.FlashcardRepositories
import com.example.chinese_flashcard.core.domain.DailyWordChoices
import com.example.chinese_flashcard.core.domain.StudySettings
import com.example.chinese_flashcard.core.domain.UiMessageKey
import com.example.chinese_flashcard.core.ui.flashcardMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StartupState(val ready: Boolean = false, val busy: Boolean = false,
  val settings: StudySettings = StudySettings(), val error: UiMessageKey? = null)
class StartupViewModel(private val repositories: FlashcardRepositories) : ViewModel() {
  private val mutable = MutableStateFlow(StartupState())
  val state = mutable.asStateFlow()
  init { prepare() }
  fun prepare() = run {
    repositories.prepare()
    repositories.settings.settings.first()
  }
  fun welcome(value: StudySettings) = run {
    val saved = value.copy(welcomed = true)
    require(DailyWordChoices.isAllowed(saved.dailyWords))
    saved.validate()
    repositories.settings.save(saved)
    saved
  }
  private fun run(operation: suspend () -> StudySettings) {
    if (mutable.value.busy) return
    mutable.value = mutable.value.copy(busy = true, error = null)
    viewModelScope.launch {
      try {
        val value = withContext(Dispatchers.IO) { operation() }
        mutable.value = StartupState(ready = true, settings = value)
      } catch (error: CancellationException) { throw error }
      catch (error: Exception) {
        val message = if (!mutable.value.ready) repositories.defaultWordlistState.value.error
          ?: error.flashcardMessage() else error.flashcardMessage()
        mutable.value = mutable.value.copy(busy = false, error = message)
      }
    }
  }
}
