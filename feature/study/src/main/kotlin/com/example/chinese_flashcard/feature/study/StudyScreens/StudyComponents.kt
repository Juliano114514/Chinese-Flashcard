package com.example.chinese_flashcard.feature.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.example.chinese_flashcard.core.ui.flashcardBackground
import com.example.chinese_flashcard.core.ui.FlashcardStyle

@Composable
internal fun WritingNavigation(state: StudyUiState, vm: StudyViewModel, onWriting: (String) -> Unit) {
  val request = state.writingRequest
  LaunchedEffect(request?.token) {
    if (request != null) {
      vm.consumeWriting(request.token)
      onWriting(request.sessionId)
    }
  }
}

@Composable
internal fun DailyWritingInvitation(state: StudyUiState, vm: StudyViewModel) {
  if (state.dailyInvitation) AlertDialog(onDismissRequest = vm::dismissInvitation,
    title = { Text("Write today's words?") },
    shape = MaterialTheme.shapes.large, containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
    text = { Text("${state.today?.todayNewWords?.size ?: 0} new words") },
    confirmButton = { TextButton(onClick = vm::startDailyWriting, enabled = !state.busy) { Text("Write now") } },
    dismissButton = { TextButton(onClick = vm::dismissInvitation, enabled = !state.busy) { Text("Not now") } })
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
    Text("Loading…", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
      color = if (studyPage) FlashcardStyle.colors.gradientSecondaryInk else MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
internal fun ErrorNotice(message: String, enabled: Boolean, onRetry: () -> Unit, studyPage: Boolean = false) {
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(message, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
      color = if (studyPage) FlashcardStyle.colors.gradientError else MaterialTheme.colorScheme.error,
      style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = onRetry, enabled = enabled, colors = ButtonDefaults.textButtonColors(
      contentColor = if (studyPage) FlashcardStyle.colors.gradientAction else MaterialTheme.colorScheme.primary)) { Text("Try again") }
  }
}
