package com.example.chinese_flashcard.feature.study

import com.example.chinese_flashcard.core.ui.uiString
import com.example.chinese_flashcard.core.ui.R as UiR

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.chinese_flashcard.core.ui.FlashcardDialog
import com.example.chinese_flashcard.core.ui.flashcardBackground
import com.example.chinese_flashcard.core.ui.FlashcardStyle
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.UiMessageKey

@Composable
internal fun WritingNavigation(state: StudyUiState, vm: StudyViewModel, onWriting: (String) -> Unit) {
  val request = state.writingRequest
  LaunchedEffect(request?.token, state.directionGeneration) {
    if (request != null &&
      vm.state.value.directionGeneration == state.directionGeneration && vm.state.value.writingRequest?.token == request.token) {
      vm.consumeWriting(request.token)
      onWriting(request.sessionId)
    }
  }
}

@Composable
internal fun DailyWritingInvitation(state: StudyUiState, vm: StudyViewModel) {
  if (state.dailyInvitation && state.settings.learningLanguage == AppLanguage.CN) FlashcardDialog(onDismissRequest = vm::dismissInvitation,
    title = { Text(uiString(UiR.string.ui_write_today_s_words)) },
    text = { Text(uiString(UiR.string.ui_new_word_count, state.today?.todayNewWords?.size ?: 0)) },
    confirmButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = vm::startDailyWriting, enabled = !state.busy) { Text(uiString(UiR.string.ui_write_now)) } },
    dismissButton = { TextButton(shape = MaterialTheme.shapes.small, onClick = vm::dismissInvitation, enabled = !state.busy) { Text(uiString(UiR.string.ui_not_now)) } })
}

@Composable
internal fun DailyProgress(completed: Int, planned: Int, modifier: Modifier = Modifier) {
  LinearProgressIndicator(progress = {
    if (planned <= 0) 0f else (completed.toFloat() / planned).coerceIn(0f, 1f)
  }, modifier = modifier.fillMaxWidth().height(4.dp))
}

@Composable
internal fun PageColumn(modifier: Modifier = Modifier, scrollKey: Any? = null, studyPage: Boolean = false,
  content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
  val scroll = rememberScrollState()
  LaunchedEffect(scrollKey) { scroll.scrollTo(0) }
  Column(modifier.fillMaxSize().then(if (studyPage) Modifier else Modifier.flashcardBackground())
    .verticalScroll(scroll).padding(horizontal = 20.dp, vertical = if (studyPage) 12.dp else 20.dp),
    verticalArrangement = Arrangement.spacedBy(if (studyPage) 8.dp else 16.dp), content = content)
}

@Composable
internal fun SectionLabel(value: String) {
  Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun LoadingNotice(studyPage: Boolean = false) {
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp)) {
    CircularProgressIndicator(Modifier.size(28.dp))
    Text(uiString(UiR.string.ui_loading), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
      color = if (studyPage) FlashcardStyle.colors.gradientSecondaryInk else MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
internal fun ErrorNotice(message: UiMessageKey, enabled: Boolean, onRetry: () -> Unit, studyPage: Boolean = false) {
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(uiString(message), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
      color = if (studyPage) FlashcardStyle.colors.gradientError else MaterialTheme.colorScheme.error,
      style = MaterialTheme.typography.bodyMedium)
    TextButton(shape = MaterialTheme.shapes.small, onClick = onRetry, enabled = enabled, colors = ButtonDefaults.textButtonColors(
      contentColor = if (studyPage) FlashcardStyle.colors.gradientAction else MaterialTheme.colorScheme.primary)) { Text(uiString(UiR.string.ui_try_again)) }
  }
}
