package com.example.chinese_flashcard.feature.wordlist

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.chinese_flashcard.core.domain.ExampleSentence
import com.example.chinese_flashcard.core.domain.Meaning
import com.example.chinese_flashcard.core.domain.WordEntry
import com.example.chinese_flashcard.core.domain.WordPart
import com.example.chinese_flashcard.core.ui.FlashcardStyle
import com.example.chinese_flashcard.core.ui.FlashcardTheme
import com.example.chinese_flashcard.core.ui.WordExplanation
import com.example.chinese_flashcard.core.ui.WordHeading
import com.example.chinese_flashcard.core.ui.WordMeanings
import com.example.chinese_flashcard.core.ui.studyBackgroundBrush
import kotlinx.coroutines.awaitCancellation

@Composable
fun WordDetailScreen(vm: WordDetailViewModel, onBack: () -> Unit, onWriting: (String) -> Unit, onSpeak: (String) -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val latestSpeak by rememberUpdatedState(onSpeak)
  BackHandler(enabled = state.busy) { /* Finish opening the writing session before leaving. */ }
  LaunchedEffect(vm, state.word?.id, state.loading, lifecycle) {
    val word = state.word
    if (word != null && !state.loading) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      if (vm.claimAutoplay()) latestSpeak(word.hanzi)
      awaitCancellation()
    }
  }
  LaunchedEffect(state.writingSessionId) {
    state.writingSessionId?.let { id -> if (vm.claimWriting(id)) onWriting(id) }
  }
  WordDetailContent(state, onBack, onWrite = vm::write, onRetry = vm::retry, onSpeak = onSpeak)
}

@Composable
private fun WordDetailContent(state: WordDetailUiState, onBack: () -> Unit,
  onWrite: () -> Unit, onRetry: () -> Unit, onSpeak: (String) -> Unit) {
  val word = state.word
  val enabled = !state.loading && !state.busy && state.writingSessionId == null
  Box(Modifier.fillMaxSize().background(studyBackgroundBrush())) {
    Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
      Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, enabled = !state.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text("Word", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(48.dp))
      }
    }, bottomBar = {
      if (word != null) Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedButton(onClick = { onSpeak(word.hanzi) }, enabled = enabled,
            shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = FlashcardStyle.colors.gradientAction),
            border = BorderStroke(1.dp, FlashcardStyle.colors.gradientAction)) { Text("Listen") }
          Button(onClick = onWrite, enabled = enabled, shape = RoundedCornerShape(8.dp),
            modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Write") }
        }
      }
    }) { padding ->
      Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.loading) {
          CircularProgressIndicator()
          Text("Loading…", color = FlashcardStyle.colors.gradientSecondaryInk)
        } else if (word != null) {
          WordHeading(word, enabled, onSpeak = { onSpeak(word.hanzi) })
          WordMeanings(word)
          WordExplanation(word, onSpeak)
        }
        state.error?.let { message ->
          Text(message, color = FlashcardStyle.colors.gradientError)
          TextButton(onClick = onRetry, enabled = !state.busy,
            colors = ButtonDefaults.textButtonColors(contentColor = FlashcardStyle.colors.gradientAction)) { Text("Try again") }
        }
      }
    }
  }
}

@Preview(name = "Word detail · light", widthDp = 360, heightDp = 760)
@Preview(name = "Word detail · dark", widthDp = 360, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Word detail · compact", widthDp = 320, heightDp = 760, fontScale = 1.3f)
@Preview(name = "Word detail · compact dark", widthDp = 320, heightDp = 760, fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WordDetailPreview() {
  val word = WordEntry("preview-study", "学习", "xuéxí", listOf(Meaning("study", "to study; to learn", "verb")),
    examples = listOf(ExampleSentence("我每天学习中文。", "Wǒ měitiān xuéxí Zhōngwén.", "I study Chinese every day.")),
    parts = listOf(WordPart("学", "xué", "learn"), WordPart("习", "xí", "practise")),
    note = "Use with the subject you are learning.", distractorMeaningIds = emptyList())
  FlashcardTheme {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      WordDetailContent(WordDetailUiState(loading = false, word = word), onBack = {}, onWrite = {}, onRetry = {}, onSpeak = {})
    }
  }
}
