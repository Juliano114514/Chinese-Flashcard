package com.example.chinese_flashcard.feature.wordlist

import com.example.chinese_flashcard.core.ui.uiString
import com.example.chinese_flashcard.core.ui.R as UiR

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.chinese_flashcard.core.domain.ExampleChunk
import com.example.chinese_flashcard.core.domain.ExampleSentence
import com.example.chinese_flashcard.core.domain.AppLanguage
import com.example.chinese_flashcard.core.domain.SpeechRequest
import com.example.chinese_flashcard.core.domain.SpeechScene
import com.example.chinese_flashcard.core.domain.Meaning
import com.example.chinese_flashcard.core.domain.WordEntry
import com.example.chinese_flashcard.core.domain.WordPart
import com.example.chinese_flashcard.core.ui.FlashcardStyle
import com.example.chinese_flashcard.core.ui.FlashcardToolbar
import com.example.chinese_flashcard.core.ui.FlashcardLayout
import com.example.chinese_flashcard.core.ui.FlashcardTheme
import com.example.chinese_flashcard.core.ui.StageThemePreviewCase
import com.example.chinese_flashcard.core.ui.StageThemePreviewProvider
import com.example.chinese_flashcard.core.ui.WordExplanation
import com.example.chinese_flashcard.core.ui.glassPanel
import com.example.chinese_flashcard.core.ui.glassPanelColor
import com.example.chinese_flashcard.core.ui.WordHeading
import com.example.chinese_flashcard.core.ui.WordMeanings
import com.example.chinese_flashcard.core.ui.WordUserActions
import com.example.chinese_flashcard.core.ui.flashcardBackground
import kotlinx.coroutines.awaitCancellation

@Composable
fun WordDetailScreen(vm: WordDetailViewModel, onBack: () -> Unit, onWriting: (String) -> Unit, onSpeak: (SpeechRequest) -> Unit,
  ipaResolver: (String) -> String? = { null }, onPreload: (List<String>) -> Unit = {}) {
  val state by vm.state.collectAsStateWithLifecycle()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val latestSpeak by rememberUpdatedState(onSpeak)
  val latestPreload by rememberUpdatedState(onPreload)
  LaunchedEffect(state.word?.id) {
    state.word?.let { latestPreload(listOfNotNull(it.meanings.firstOrNull()?.english, it.examples.firstOrNull()?.english)) }
  }
  BackHandler(enabled = state.busy) { /* Finish opening the writing session before leaving. */ }
  LaunchedEffect(vm, state.word?.id, state.loading, lifecycle) {
    val word = state.word
    if (word != null && !state.loading) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      if (vm.claimAutoplay()) latestSpeak(SpeechRequest(
        if (state.learningLanguage == AppLanguage.CN) word.hanzi else word.meanings.firstOrNull()?.english.orEmpty(),
        state.learningLanguage, SpeechScene.WORD_DETAIL,
        if (state.learningLanguage == AppLanguage.CN) word.meanings.firstOrNull()?.english else word.hanzi))
      awaitCancellation()
    }
  }
  LaunchedEffect(state.writingSessionId) {
    state.writingSessionId?.let { id -> if (vm.claimWriting(id)) onWriting(id) }
  }
  WordDetailContent(state, onBack, onWrite = vm::write, onRetry = vm::retry, onSpeak = onSpeak,
    onCollection = vm::setCollected, onSkip = vm::setSkipped, ipaResolver = ipaResolver)
}

@Composable
private fun WordDetailContent(state: WordDetailUiState, onBack: () -> Unit,
  onWrite: () -> Unit, onRetry: () -> Unit, onSpeak: (SpeechRequest) -> Unit,
  onCollection: (Boolean) -> Unit = {}, onSkip: (Boolean) -> Unit = {}, ipaResolver: (String) -> String? = { null }) {
  val word = state.word
  val enabled = !state.loading && !state.busy && state.writingSessionId == null
  Box(Modifier.fillMaxSize().flashcardBackground()) {
    Scaffold(containerColor = Color.Transparent,
      contentWindowInsets = if (word != null) WindowInsets(0, 0, 0, 0)
        else WindowInsets.navigationBars.only(WindowInsetsSides.Bottom), topBar = {
      FlashcardToolbar(uiString(UiR.string.ui_word), onBack, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), enabled = !state.busy)
    }, bottomBar = {
      if (word != null) Column(Modifier.fillMaxWidth().glassPanel(androidx.compose.ui.graphics.RectangleShape)
        .background(glassPanelColor()).navigationBarsPadding()
        .padding(horizontal = FlashcardLayout.pageInset, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedButton(onClick = { onSpeak(SpeechRequest(word.hanzi, AppLanguage.CN,
            counterpart = word.meanings.firstOrNull()?.english)) }, enabled = enabled,
            shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).heightIn(min = FlashcardLayout.rowHeight),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = FlashcardStyle.colors.gradientAction),
            border = BorderStroke(1.dp, FlashcardStyle.colors.gradientAction)) { Text(uiString(UiR.string.ui_listen)) }
          Button(onClick = onWrite, enabled = enabled, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.weight(1f).heightIn(min = FlashcardLayout.rowHeight)) { Text(uiString(UiR.string.ui_write)) }
        }
      }
    }) { padding ->
      Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.loading) {
          Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator()
            Text(uiString(UiR.string.ui_loading), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
              color = FlashcardStyle.colors.gradientSecondaryInk)
          }
        } else if (word != null) {
          WordHeading(word, enabled, onSpeak = { onSpeak(SpeechRequest(word.hanzi, AppLanguage.CN,
            counterpart = word.meanings.firstOrNull()?.english)) },
            trailingOnNewLine = word.hanzi.codePointCount(0, word.hanzi.length) > 2 || LocalDensity.current.fontScale >= 1.25f,
            trailing = {
            WordUserActions(state.userState.isCollected, state.userState.isSkipped, enabled,
              onCollection, onSkip, wordId = word.id, wordLabel = word.hanzi)
          })
          WordMeanings(word, onSpeak, ipaResolver, enabled)
          WordExplanation(word, onSpeak, enabled = enabled, progressive = true)
        }
        state.error?.let { message ->
          Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(uiString(message), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
              color = FlashcardStyle.colors.gradientError)
            TextButton(shape = MaterialTheme.shapes.small, onClick = onRetry, enabled = !state.busy,
              colors = ButtonDefaults.textButtonColors(contentColor = FlashcardStyle.colors.gradientAction)) { Text(uiString(UiR.string.ui_try_again)) }
          }
        }
      }
    }
  }
}

@Preview(name = "Word detail", widthDp = 360, heightDp = 760)
@Composable
private fun WordDetailPreview(@PreviewParameter(StageThemePreviewProvider::class, limit = 1) theme: StageThemePreviewCase) {
  val word = WordEntry("preview-study", "学习", "xuéxí", listOf(Meaning("study", "to study; to learn", "verb")),
    examples = listOf(ExampleSentence("我每天学习中文。", "Wǒ měitiān xuéxí Zhōngwén.", "I study Chinese every day.",
      listOf(ExampleChunk("我", "wǒ", "I"), ExampleChunk("每天", "měitiān", "every day"),
        ExampleChunk("学习", "xuéxí", "study"), ExampleChunk("中文。", "Zhōngwén.", "Chinese")))),
    parts = listOf(WordPart("学", "xué", "learn"), WordPart("习", "xí", "practise")),
    distractorMeaningIds = emptyList(), literalExplanations = listOf("Gain knowledge or skills by studying.", "Learn from another person."))
  FlashcardTheme(darkTheme = theme.darkTheme, stage = theme.stage) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      WordDetailContent(WordDetailUiState(loading = false, word = word), onBack = {}, onWrite = {}, onRetry = {}, onSpeak = {})
    }
  }
}
